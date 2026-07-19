package com.jobpilot.resume.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.Hashing;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.llm.api.LlmProvider;
import com.jobpilot.llm.api.LlmProviderException;
import com.jobpilot.llm.api.LlmRequest;
import com.jobpilot.llm.api.LlmResponse;
import com.jobpilot.llm.application.LlmUsageRecorder;
import com.jobpilot.llm.budget.LlmBudgetDecision;
import com.jobpilot.llm.budget.LlmBudgetReservation;
import com.jobpilot.llm.budget.LlmBudgetReservationResult;
import com.jobpilot.llm.budget.LlmBudgetService;
import com.jobpilot.llm.budget.LlmCostCalculator;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.llm.domain.LlmOperationType;
import com.jobpilot.llm.domain.LlmUsageStatus;
import com.jobpilot.resume.domain.DocumentGenerationMethod;
import com.jobpilot.resume.domain.ResumeDocumentModel;
import com.jobpilot.resume.validation.CoverNoteTruthValidationException;
import com.jobpilot.resume.validation.CoverNoteTruthValidator;
import com.jobpilot.resume.validation.ResumeTruthValidationException;
import com.jobpilot.resume.validation.ResumeTruthValidator;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentLlmDraftService {
    private final JobRepository jobs;
    private final DocumentDraftPromptBuilder prompts;
    private final ResumeTruthValidator resumeValidator;
    private final CoverNoteTruthValidator coverValidator;
    private final LlmBudgetService budget;
    private final LlmCostCalculator costs;
    private final LlmUsageRecorder usage;
    private final LlmProvider provider;
    private final ObjectMapper mapper;
    private final JobPilotProperties.Llm settings;
    private final TransactionTemplate transactions;

    public DocumentLlmDraftService(JobRepository jobs, DocumentDraftPromptBuilder prompts,
                                   ResumeTruthValidator resumeValidator,
                                   CoverNoteTruthValidator coverValidator,
                                   LlmBudgetService budget, LlmCostCalculator costs,
                                   LlmUsageRecorder usage, LlmProvider provider,
                                   ObjectMapper mapper, JobPilotProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.prompts = prompts;
        this.resumeValidator = resumeValidator;
        this.coverValidator = coverValidator;
        this.budget = budget;
        this.costs = costs;
        this.usage = usage;
        this.provider = provider;
        this.mapper = mapper;
        this.settings = properties.llm();
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public DocumentDraftOutcome<ResumeDraftPlan> resume(
            CandidateDocumentFacts facts, JobDocumentFacts job,
            ResumeDraftPlan deterministic, String documentIdentity) {
        DocumentDraftPrompt prompt;
        try {
            prompt = prompts.resume(facts, job);
        } catch (RuntimeException boundedInput) {
            recordSkipped(job.jobId(), LlmOperationType.RESUME_DRAFT,
                    requestKey(documentIdentity, LlmOperationType.RESUME_DRAFT),
                    LlmFailureCategory.CALL_LIMIT);
            return DocumentDraftOutcome.fallback(deterministic, LlmFailureCategory.CALL_LIMIT);
        }
        return execute(job.jobId(), LlmOperationType.RESUME_DRAFT, documentIdentity, prompt,
                deterministic, ResumeDraftPlan.class,
                value -> resumeValidator.validatePlan(value, facts, job));
    }

    public DocumentDraftOutcome<CoverNoteDraftPlan> coverNote(
            CandidateDocumentFacts facts, JobDocumentFacts job,
            ResumeDocumentModel resume, CoverNoteDraftPlan deterministic,
            String documentIdentity) {
        DocumentDraftPrompt prompt;
        try {
            prompt = prompts.coverNote(facts, job, resume);
        } catch (RuntimeException boundedInput) {
            recordSkipped(job.jobId(), LlmOperationType.COVER_NOTE_DRAFT,
                    requestKey(documentIdentity, LlmOperationType.COVER_NOTE_DRAFT),
                    LlmFailureCategory.CALL_LIMIT);
            return DocumentDraftOutcome.fallback(deterministic, LlmFailureCategory.CALL_LIMIT);
        }
        return execute(job.jobId(), LlmOperationType.COVER_NOTE_DRAFT, documentIdentity, prompt,
                deterministic, CoverNoteDraftPlan.class,
                value -> coverValidator.validatePlan(value, facts, job, resume));
    }

    private <T> DocumentDraftOutcome<T> execute(long jobId, LlmOperationType operation,
                                                 String identity, DocumentDraftPrompt prompt,
                                                 T deterministic, Class<T> resultType,
                                                 PlanValidator<T> validator) {
        String requestKey = requestKey(identity, operation);
        if (!settings.enabled()) {
            recordSkipped(jobId, operation, requestKey, LlmFailureCategory.DISABLED);
            return DocumentDraftOutcome.fallback(deterministic, LlmFailureCategory.DISABLED);
        }
        LlmBudgetReservationResult reservation;
        try {
            reservation = transactions.execute(status -> {
                Job job = jobs.findById(jobId).orElseThrow();
                return budget.reserveWithinTransaction(job, operation, requestKey);
            });
        } catch (RuntimeException databaseFailure) {
            return DocumentDraftOutcome.fallback(deterministic, LlmFailureCategory.DATABASE_ERROR);
        }
        if (!reservation.reserved()) {
            LlmFailureCategory category = reservation.decision() == LlmBudgetDecision.DUPLICATE
                    ? LlmFailureCategory.CONCURRENT_REQUEST : LlmFailureCategory.BUDGET_EXHAUSTED;
            if (reservation.decision() != LlmBudgetDecision.DUPLICATE) {
                recordSkipped(jobId, operation, requestKey, category);
            }
            return DocumentDraftOutcome.fallback(deterministic, category);
        }
        long reservationId = reservation.reservation().getId();
        try {
            budget.markProviderStarted(reservationId);
        } catch (RuntimeException databaseFailure) {
            return DocumentDraftOutcome.fallback(deterministic, LlmFailureCategory.DATABASE_ERROR);
        }

        LlmResponse response;
        try {
            response = provider.execute(new LlmRequest(prompt.trustedInstructions(),
                    prompt.candidateFactsJson(), prompt.vacancyDataJson(), prompt.schema(),
                    settings.maxOutputTokens()));
        } catch (LlmProviderException failure) {
            finishFailure(jobId, operation, requestKey, reservationId, null,
                    failure.getCategory(), failure.getAmbiguousAttempts());
            return DocumentDraftOutcome.fallback(deterministic, failure.getCategory());
        } catch (RuntimeException unexpected) {
            finishFailure(jobId, operation, requestKey, reservationId, null,
                    LlmFailureCategory.PROVIDER_ERROR, 1);
            return DocumentDraftOutcome.fallback(deterministic, LlmFailureCategory.PROVIDER_ERROR);
        }

        final T selected;
        try {
            selected = mapper.readerFor(resultType)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(response.structuredJson());
            validator.validate(selected);
        } catch (JsonProcessingException malformed) {
            finishFailure(jobId, operation, requestKey, reservationId, response,
                    LlmFailureCategory.MALFORMED_RESPONSE, 0);
            return DocumentDraftOutcome.fallback(deterministic, LlmFailureCategory.MALFORMED_RESPONSE);
        } catch (ResumeTruthValidationException | CoverNoteTruthValidationException unsupported) {
            finishFailure(jobId, operation, requestKey, reservationId, response,
                    LlmFailureCategory.TRUTHFULNESS_VALIDATION, 0);
            return DocumentDraftOutcome.fallback(deterministic,
                    LlmFailureCategory.TRUTHFULNESS_VALIDATION);
        }
        if (!finishSuccess(jobId, operation, requestKey, reservationId, response)) {
            return DocumentDraftOutcome.fallback(deterministic, LlmFailureCategory.DATABASE_ERROR);
        }
        return new DocumentDraftOutcome<>(selected, DocumentGenerationMethod.PROVIDER, false, null);
    }

    private boolean finishSuccess(long jobId, LlmOperationType operation, String requestKey,
                                  long reservationId, LlmResponse response) {
        Accounting accounting = accounting(response, 0);
        try {
            transactions.executeWithoutResult(status -> {
                Job job = jobs.findById(jobId).orElseThrow();
                LlmBudgetReservation reserved = budget.reconcileWithinTransaction(
                        reservationId, accounting.cost());
                usage.record(reserved, requestKey, job, operation, settings.provider(),
                        settings.model(), accounting.inputTokens(), accounting.outputTokens(),
                        accounting.estimated(), reserved.getFinalCostUsd(),
                        LlmUsageStatus.SUCCEEDED, false, null);
            });
            return true;
        } catch (RuntimeException databaseFailure) {
            return false;
        }
    }

    private void finishFailure(long jobId, LlmOperationType operation, String requestKey,
                               long reservationId, LlmResponse response,
                               LlmFailureCategory category, int ambiguousAttempts) {
        Accounting accounting = response == null
                ? conservative(ambiguousAttempts) : accounting(response, 0);
        try {
            transactions.executeWithoutResult(status -> {
                Job job = jobs.findById(jobId).orElseThrow();
                LlmBudgetReservation reserved = budget.reconcileFailureWithinTransaction(
                        reservationId, accounting.cost());
                usage.record(reserved, requestKey, job, operation, settings.provider(),
                        settings.model(), accounting.inputTokens(), accounting.outputTokens(),
                        accounting.estimated(), reserved.getFinalCostUsd(),
                        LlmUsageStatus.FAILED, true, category);
            });
        } catch (RuntimeException ignored) {
            // The generation remains a deterministic fallback with sanitized database failure state.
        }
    }

    private void recordSkipped(long jobId, LlmOperationType operation, String requestKey,
                               LlmFailureCategory category) {
        try {
            transactions.executeWithoutResult(status -> {
                Job job = jobs.findById(jobId).orElseThrow();
                String providerName = settings.enabled() ? settings.provider() : "disabled";
                String modelName = settings.enabled() ? settings.model() : "disabled";
                usage.record(null, requestKey, job, operation, providerName, modelName,
                        null, null, false, BigDecimal.ZERO, LlmUsageStatus.SKIPPED,
                        true, category);
            });
        } catch (RuntimeException ignored) {
            // Drafting can still fail closed to deterministic output if sanitized accounting cannot persist.
        }
    }

    private Accounting accounting(LlmResponse response, int additionalAttempts) {
        int priorAttempts = response.ambiguousAttemptsBeforeFinal() + additionalAttempts;
        if (!response.hasTokenUsage() || response.inputTokens() > 10_000_000L
                || response.outputTokens() > 1_000_000L) {
            return conservative(priorAttempts + 1);
        }
        long input = Math.addExact(response.inputTokens(),
                Math.multiplyExact(settings.maxInputTokens().longValue(), priorAttempts));
        long output = Math.addExact(response.outputTokens(),
                Math.multiplyExact(settings.maxOutputTokens().longValue(), priorAttempts));
        BigDecimal cost = costs.cost(response.inputTokens(), response.outputTokens())
                .add(costs.maximumSingleAttemptCost().multiply(BigDecimal.valueOf(priorAttempts)))
                .setScale(LlmCostCalculator.COST_SCALE, java.math.RoundingMode.CEILING);
        return new Accounting(input, output, priorAttempts > 0, cost);
    }

    private Accounting conservative(int attempts) {
        int boundedAttempts = Math.max(1, Math.min(attempts, settings.maxRetries() + 1));
        long input = Math.multiplyExact(settings.maxInputTokens().longValue(), boundedAttempts);
        long output = Math.multiplyExact(settings.maxOutputTokens().longValue(), boundedAttempts);
        BigDecimal cost = costs.maximumSingleAttemptCost()
                .multiply(BigDecimal.valueOf(boundedAttempts))
                .setScale(LlmCostCalculator.COST_SCALE, java.math.RoundingMode.CEILING);
        return new Accounting(input, output, true, cost);
    }

    private String requestKey(String identity, LlmOperationType operation) {
        return Hashing.sha256(identity + "|" + operation.name());
    }

    @FunctionalInterface
    private interface PlanValidator<T> {
        void validate(T value);
    }

    private record Accounting(long inputTokens, long outputTokens,
                              boolean estimated, BigDecimal cost) {
    }
}
