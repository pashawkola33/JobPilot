package com.jobpilot.llm.application;

import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.llm.api.LlmProvider;
import com.jobpilot.llm.api.LlmProviderException;
import com.jobpilot.llm.api.LlmRequest;
import com.jobpilot.llm.api.LlmResponse;
import com.jobpilot.llm.budget.LlmBudgetDecision;
import com.jobpilot.llm.budget.LlmBudgetReservation;
import com.jobpilot.llm.budget.LlmBudgetReservationResult;
import com.jobpilot.llm.budget.LlmBudgetService;
import com.jobpilot.llm.budget.LlmCostCalculator;
import com.jobpilot.llm.domain.JobAnalysis;
import com.jobpilot.llm.domain.JobAnalysisData;
import com.jobpilot.llm.domain.JobAnalysisJson;
import com.jobpilot.llm.domain.JobAnalysisResult;
import com.jobpilot.llm.domain.JobAnalysisResultStatus;
import com.jobpilot.llm.domain.JobAnalysisStatus;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.llm.domain.LlmOperationType;
import com.jobpilot.llm.domain.LlmUsageStatus;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(JobAnalysisService.class);
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(5);
    private static final LlmOperationType OPERATION = LlmOperationType.JOB_ANALYSIS;

    private final JobRepository jobs;
    private final JobRequirementRepository requirements;
    private final CandidateProfileRepository profiles;
    private final JobAnalysisRepository analyses;
    private final JobAnalysisJson analysisJson;
    private final JobAnalysisPromptBuilder prompts;
    private final JobAnalysisCacheKey keys;
    private final LlmStructuredResponseValidator validator;
    private final DeterministicJobAnalysisFallback fallback;
    private final LlmBudgetService budget;
    private final LlmCostCalculator costs;
    private final LlmUsageRecorder usage;
    private final LlmProvider provider;
    private final JobPilotProperties.Llm settings;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public JobAnalysisService(JobRepository jobs, JobRequirementRepository requirements,
                              CandidateProfileRepository profiles, JobAnalysisRepository analyses,
                              JobAnalysisJson analysisJson, JobAnalysisPromptBuilder prompts,
                              JobAnalysisCacheKey keys,
                              LlmStructuredResponseValidator validator,
                              DeterministicJobAnalysisFallback fallback,
                              LlmBudgetService budget, LlmCostCalculator costs,
                              LlmUsageRecorder usage, LlmProvider provider,
                              JobPilotProperties properties, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.requirements = requirements;
        this.profiles = profiles;
        this.analyses = analyses;
        this.analysisJson = analysisJson;
        this.prompts = prompts;
        this.keys = keys;
        this.validator = validator;
        this.fallback = fallback;
        this.budget = budget;
        this.costs = costs;
        this.usage = usage;
        this.provider = provider;
        this.settings = properties.llm();
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public JobAnalysisResult analyze(long jobId, boolean candidateSpecific) {
        final Preparation preparation;
        try {
            preparation = transactions.execute(status -> prepare(jobId, candidateSpecific));
        } catch (DataAccessException | IllegalStateException databaseFailure) {
            return unavailable(jobId, LlmFailureCategory.DATABASE_ERROR);
        }
        if (preparation.immediate() != null) return preparation.immediate();

        try {
            budget.markProviderStarted(preparation.reservationId());
        } catch (RuntimeException databaseFailure) {
            return finalizeFailure(preparation, null, LlmFailureCategory.DATABASE_ERROR, 0);
        }

        LlmResponse providerResponse;
        try {
            providerResponse = provider.execute(preparation.request());
        } catch (LlmProviderException providerFailure) {
            return finalizeFailure(preparation, null, providerFailure.getCategory(),
                    providerFailure.getAmbiguousAttempts());
        } catch (RuntimeException unexpectedProviderFailure) {
            return finalizeFailure(preparation, null, LlmFailureCategory.PROVIDER_ERROR, 1);
        }

        final JobAnalysisData validated;
        try {
            validated = validator.validate(providerResponse.structuredJson(), preparation.input());
        } catch (JobAnalysisValidationException invalid) {
            return finalizeFailure(preparation, providerResponse,
                    LlmFailureCategory.TRUTHFULNESS_VALIDATION, 0);
        }
        return finalizeSuccess(preparation, providerResponse, validated);
    }

    private Preparation prepare(long jobId, boolean candidateSpecific) {
        Instant now = clock.instant();
        Job job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (job == null) return Preparation.immediate(result(
                JobAnalysisResultStatus.JOB_NOT_FOUND, null, jobId, null, null, null));
        ExtractedRequirements extracted = requirements.findByJobId(jobId)
                .map(requirement -> requirement.toValue()).orElse(null);
        if (extracted == null) {
            return Preparation.immediate(result(JobAnalysisResultStatus.PROVIDER_FAILED,
                    null, jobId, null, null, LlmFailureCategory.CONFIGURATION));
        }
        CandidateProfile profile = null;
        CandidateTruthSnapshot truth = null;
        if (candidateSpecific) {
            profile = profiles.findByActiveTrue().orElse(null);
            if (profile == null) return Preparation.immediate(result(
                    JobAnalysisResultStatus.PROFILE_NOT_FOUND, null, jobId,
                    null, null, null));
            truth = CandidateTruthSnapshot.from(profile);
        }
        String jobHash = keys.jobContentHash(job, extracted);
        JobAnalysisInput input = new JobAnalysisInput(job.getId(), job.getTitle(), job.getCompany(),
                job.getLocation(), job.getDescription(), jobHash, extracted, truth);
        String providerName = settings.enabled() ? settings.provider() : "disabled";
        String modelName = settings.enabled() ? settings.model() : "disabled";
        String cacheKey = keys.cacheKey(jobHash, truth, OPERATION,
                JobAnalysisPromptBuilder.PROMPT_VERSION, providerName, modelName);
        JobAnalysis existing = analyses.findByCacheKeyForUpdate(cacheKey).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == JobAnalysisStatus.SUCCEEDED) {
                return Preparation.immediate(toResult(existing, JobAnalysisResultStatus.CACHED));
            }
            if (existing.getStatus() == JobAnalysisStatus.IN_PROGRESS
                    && !stale(existing, now)) {
                return Preparation.immediate(result(JobAnalysisResultStatus.FALLBACK,
                        existing.getId(), jobId, existing.getCandidateProfileVersion(),
                        fallback.analyze(input), LlmFailureCategory.CONCURRENT_REQUEST));
            }
            if (existing.getStatus() == JobAnalysisStatus.FALLBACK
                    && (existing.getRetryAfter() == null || existing.getRetryAfter().isAfter(now))) {
                return Preparation.immediate(toResult(existing,
                        resultStatus(existing.getFailureCategory())));
            }
            existing.beginRetry(now);
        }
        int attempt = existing == null ? 1 : existing.getAttemptCount();
        String requestKey = keys.requestKey(cacheKey, attempt);
        if (!settings.enabled()) {
            JobAnalysis analysis = existing == null ? new JobAnalysis(job, profile, OPERATION,
                    providerName, modelName, JobAnalysisPromptBuilder.PROMPT_VERSION,
                    jobHash, truth == null ? null : truth.truthHash(), cacheKey, now) : existing;
            JobAnalysisData deterministic = fallback.analyze(input);
            analysis.completeFallback(deterministic, LlmFailureCategory.DISABLED,
                    null, analysisJson, now);
            analyses.saveAndFlush(analysis);
            usage.record(null, requestKey, job, OPERATION, providerName, modelName,
                    null, null, false, BigDecimal.ZERO, LlmUsageStatus.SKIPPED,
                    true, LlmFailureCategory.DISABLED);
            return Preparation.immediate(toResult(analysis, JobAnalysisResultStatus.DISABLED));
        }

        final JobAnalysisPrompt prompt;
        try {
            prompt = prompts.build(input);
        } catch (JobAnalysisValidationException invalidInput) {
            JobAnalysis analysis = fallbackWithoutReservation(existing, job, profile, input,
                    providerName, modelName, jobHash, truth, cacheKey, requestKey,
                    LlmFailureCategory.CALL_LIMIT, now);
            return Preparation.immediate(toResult(analysis, JobAnalysisResultStatus.FALLBACK));
        }

        LlmBudgetReservationResult reserved = budget.reserveWithinTransaction(job, OPERATION, requestKey);
        if (!reserved.reserved()) {
            if (reserved.decision() == LlmBudgetDecision.DUPLICATE) {
                JobAnalysis concurrent = analyses.findByCacheKey(cacheKey).orElse(existing);
                return Preparation.immediate(result(JobAnalysisResultStatus.FALLBACK,
                        concurrent == null ? null : concurrent.getId(), jobId,
                        truth == null ? null : truth.profileVersion(), fallback.analyze(input),
                        LlmFailureCategory.CONCURRENT_REQUEST));
            }
            JobAnalysis analysis = fallbackWithoutReservation(existing, job, profile, input,
                    providerName, modelName, jobHash, truth, cacheKey, requestKey,
                    LlmFailureCategory.BUDGET_EXHAUSTED, now);
            return Preparation.immediate(toResult(analysis, JobAnalysisResultStatus.BUDGET_EXCEEDED));
        }
        JobAnalysis analysis = existing == null ? new JobAnalysis(job, profile, OPERATION,
                providerName, modelName, JobAnalysisPromptBuilder.PROMPT_VERSION,
                jobHash, truth == null ? null : truth.truthHash(), cacheKey, now) : existing;
        analysis.attachReservation(reserved.reservation());
        analyses.saveAndFlush(analysis);
        LlmRequest request = new LlmRequest(prompt.trustedInstructions(), prompt.candidateFactsJson(),
                prompt.vacancyDataJson(), prompt.outputSchema(), settings.maxOutputTokens());
        return new Preparation(null, analysis.getId(), reserved.reservation().getId(),
                requestKey, input, request);
    }

    private JobAnalysis fallbackWithoutReservation(
            JobAnalysis existing, Job job, CandidateProfile profile, JobAnalysisInput input,
            String providerName, String modelName, String jobHash, CandidateTruthSnapshot truth,
            String cacheKey, String requestKey, LlmFailureCategory failure, Instant now) {
        JobAnalysis analysis = existing == null ? new JobAnalysis(job, profile, OPERATION,
                providerName, modelName, JobAnalysisPromptBuilder.PROMPT_VERSION,
                jobHash, truth == null ? null : truth.truthHash(), cacheKey, now) : existing;
        analysis.completeFallback(fallback.analyze(input), failure, now.plus(FAILURE_COOLDOWN),
                analysisJson, now);
        analyses.saveAndFlush(analysis);
        usage.record(null, requestKey, job, OPERATION, providerName, modelName,
                null, null, false, BigDecimal.ZERO, LlmUsageStatus.SKIPPED, true, failure);
        return analysis;
    }

    private JobAnalysisResult finalizeSuccess(Preparation preparation, LlmResponse response,
                                              JobAnalysisData data) {
        TokenAccounting accounting = accounting(response);
        try {
            return transactions.execute(status -> {
                JobAnalysis analysis = analyses.findByIdForUpdate(preparation.analysisId()).orElseThrow();
                LlmBudgetReservation reservation = budget.reconcileWithinTransaction(
                        preparation.reservationId(), accounting.cost());
                analysis.complete(data, analysisJson, clock.instant());
                analyses.save(analysis);
                usage.record(reservation, preparation.requestKey(), analysis.getJob(), OPERATION,
                        settings.provider(), settings.model(), accounting.inputTokens(),
                        accounting.outputTokens(), accounting.estimated(),
                        reservation.getFinalCostUsd(),
                        LlmUsageStatus.SUCCEEDED, false, null);
                return toResult(analysis, JobAnalysisResultStatus.CREATED);
            });
        } catch (RuntimeException databaseFailure) {
            return unavailable(preparation.input().jobId(), LlmFailureCategory.DATABASE_ERROR);
        }
    }

    private JobAnalysisResult finalizeFailure(Preparation preparation, LlmResponse response,
                                              LlmFailureCategory category,
                                              int ambiguousAttempts) {
        TokenAccounting accounting = response == null
                ? conservativeAccounting(ambiguousAttempts) : accounting(response);
        JobAnalysisData deterministic = fallback.analyze(preparation.input());
        try {
            JobAnalysisResult stored = transactions.execute(status -> {
                JobAnalysis analysis = analyses.findByIdForUpdate(preparation.analysisId()).orElseThrow();
                LlmBudgetReservation reservation = budget.reconcileFailureWithinTransaction(
                        preparation.reservationId(), accounting.cost());
                analysis.completeFallback(deterministic, category,
                        clock.instant().plus(FAILURE_COOLDOWN), analysisJson, clock.instant());
                analyses.save(analysis);
                usage.record(reservation, preparation.requestKey(), analysis.getJob(), OPERATION,
                        settings.provider(), settings.model(), accounting.inputTokens(),
                        accounting.outputTokens(), accounting.estimated(),
                        reservation.getFinalCostUsd(),
                        LlmUsageStatus.FAILED, true, category);
                return toResult(analysis, resultStatus(category));
            });
            log.info("LLM job analysis used deterministic fallback: jobId={}, category={}",
                    preparation.input().jobId(), category);
            return stored;
        } catch (RuntimeException databaseFailure) {
            return result(JobAnalysisResultStatus.PROVIDER_FAILED, preparation.analysisId(),
                    preparation.input().jobId(), profileVersion(preparation.input()),
                    deterministic, LlmFailureCategory.DATABASE_ERROR);
        }
    }

    private TokenAccounting accounting(LlmResponse response) {
        int priorAttempts = response.ambiguousAttemptsBeforeFinal();
        if (!response.hasTokenUsage()) {
            return conservativeAccounting(priorAttempts + 1);
        }
        long input = response.inputTokens();
        long output = response.outputTokens();
        if (input > 10_000_000L || output > 1_000_000L) {
            return conservativeAccounting(priorAttempts + 1);
        }
        long totalInput = addAttempts(input, settings.maxInputTokens(), priorAttempts);
        long totalOutput = addAttempts(output, settings.maxOutputTokens(), priorAttempts);
        BigDecimal totalCost = costs.cost(input, output).add(
                costs.maximumSingleAttemptCost().multiply(BigDecimal.valueOf(priorAttempts)))
                .setScale(LlmCostCalculator.COST_SCALE, java.math.RoundingMode.CEILING);
        return new TokenAccounting(totalInput, totalOutput, priorAttempts > 0, totalCost);
    }

    private TokenAccounting conservativeAccounting(int attempts) {
        if (attempts < 0 || attempts > settings.maxRetries() + 1) {
            throw new IllegalArgumentException("LLM attempt accounting exceeded the reservation");
        }
        long input = Math.multiplyExact(settings.maxInputTokens().longValue(), attempts);
        long output = Math.multiplyExact(settings.maxOutputTokens().longValue(), attempts);
        BigDecimal cost = costs.maximumSingleAttemptCost()
                .multiply(BigDecimal.valueOf(attempts))
                .setScale(LlmCostCalculator.COST_SCALE, java.math.RoundingMode.CEILING);
        return new TokenAccounting(input, output, true, cost);
    }

    private long addAttempts(long actual, long maximumPerAttempt, int attempts) {
        return Math.addExact(actual, Math.multiplyExact(maximumPerAttempt, (long) attempts));
    }

    private boolean stale(JobAnalysis analysis, Instant now) {
        return !analysis.getCreatedAt().plus(settings.responseTimeout())
                .plus(Duration.ofMinutes(2)).isAfter(now);
    }

    private JobAnalysisResult toResult(JobAnalysis analysis, JobAnalysisResultStatus status) {
        return result(status, analysis.getId(), analysis.getJob().getId(),
                analysis.getCandidateProfileVersion(), analysis.toData(analysisJson),
                analysis.getFailureCategory());
    }

    private JobAnalysisResultStatus resultStatus(LlmFailureCategory failure) {
        if (failure == LlmFailureCategory.DISABLED) return JobAnalysisResultStatus.DISABLED;
        if (failure == LlmFailureCategory.BUDGET_EXHAUSTED
                || failure == LlmFailureCategory.CALL_LIMIT) {
            return JobAnalysisResultStatus.BUDGET_EXCEEDED;
        }
        if (failure == LlmFailureCategory.MALFORMED_RESPONSE
                || failure == LlmFailureCategory.SCHEMA_VALIDATION
                || failure == LlmFailureCategory.TRUTHFULNESS_VALIDATION
                || failure == LlmFailureCategory.RESPONSE_TOO_LARGE) {
            return JobAnalysisResultStatus.INVALID_PROVIDER_RESPONSE;
        }
        if (failure == LlmFailureCategory.CONCURRENT_REQUEST) return JobAnalysisResultStatus.FALLBACK;
        return JobAnalysisResultStatus.PROVIDER_FAILED;
    }

    private JobAnalysisResult unavailable(long jobId, LlmFailureCategory failure) {
        return result(JobAnalysisResultStatus.PROVIDER_FAILED, null, jobId, null,
                null, failure);
    }

    private Integer profileVersion(JobAnalysisInput input) {
        return input.candidateTruth() == null ? null : input.candidateTruth().profileVersion();
    }

    private JobAnalysisResult result(JobAnalysisResultStatus status, Long analysisId,
                                     Long jobId, Integer profileVersion,
                                     JobAnalysisData data, LlmFailureCategory failure) {
        return new JobAnalysisResult(status, analysisId, jobId, profileVersion, data, failure);
    }

    private record Preparation(JobAnalysisResult immediate, Long analysisId, Long reservationId,
                               String requestKey, JobAnalysisInput input, LlmRequest request) {
        static Preparation immediate(JobAnalysisResult result) {
            return new Preparation(result, null, null, null, null, null);
        }
    }

    private record TokenAccounting(long inputTokens, long outputTokens, boolean estimated,
                                   BigDecimal cost) {
    }
}
