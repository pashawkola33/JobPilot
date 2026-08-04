package com.jobpilot.matching.rescore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.JobRequirement;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.matching.JobScoreCalculator;
import com.jobpilot.matching.ScoreCalculation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One locked all-or-nothing write transaction for a previously approved immutable plan. */
@Service
public class ScoreRescoreWriteTransaction {
    private final JobScoreRepository scores;
    private final JobRequirementRepository requirements;
    private final JobScoreCalculator calculator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScoreRescoreWriteTransaction(JobScoreRepository scores,
                                        JobRequirementRepository requirements,
                                        JobScoreCalculator calculator,
                                        ObjectMapper objectMapper,
                                        Clock clock) {
        this.scores = scores;
        this.requirements = requirements;
        this.calculator = calculator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ScoreRescoreWriteResult apply(ScoreRescorePlan plan) {
        if (plan.changedCount() == 0) throw abort("Refusing an empty write plan");
        List<Long> ids = plan.changedJobIds();
        List<JobScore> lockedScores = scores.findAllByJobIdInForRescoreWrite(ids);
        List<JobRequirement> lockedRequirements =
                requirements.findAllByJobIdInForRescoreWrite(ids);
        Map<Long, JobScore> scoreByJob = scoreMap(lockedScores);
        Map<Long, JobRequirement> requirementByJob = requirementMap(lockedRequirements);
        if (scoreByJob.size() != ids.size() || requirementByJob.size() != ids.size()) {
            throw abort("A planned target row is missing");
        }

        List<LockedTarget> targets = new ArrayList<>(ids.size());
        List<ScoreRescorePlanEntry> reconstructed = new ArrayList<>(ids.size());
        for (ScoreRescorePlanEntry expected : plan.entries()) {
            JobScore scoreRow = scoreByJob.get(expected.jobId());
            JobRequirement requirementRow = requirementByJob.get(expected.jobId());
            if (scoreRow.getJob().getScreeningDisposition() == ScreeningDisposition.REJECT) {
                throw abort("A planned target became REJECT");
            }
            ScoreCalculation computed = calculator.calculate(scoreRow.getJob());
            ScoreRescorePlanEntry current = ScoreRescorePlanEntry.snapshot(
                    scoreRow, requirementRow, computed);
            if (!current.equals(expected)) {
                throw abort("A planned target changed after planning");
            }
            String rawJson = json(computed.requirements());
            reconstructed.add(current);
            targets.add(new LockedTarget(scoreRow, requirementRow, computed, rawJson));
        }
        if (!ScoreRescorePlanFingerprint.fingerprint(reconstructed).equals(plan.fingerprint())) {
            throw abort("The locked plan fingerprint changed after planning");
        }

        Instant scoredAt = clock.instant();
        int requirementUpdates = 0;
        for (LockedTarget target : targets) {
            target.score().apply(target.computed().score(), scoredAt);
            ExtractedRequirements oldRequirements = target.requirement().toValue();
            ExtractedRequirements fresh = target.computed().requirements();
            if (!oldRequirements.equals(fresh)) {
                target.requirement().apply(fresh, join(fresh.technologies()),
                        join(fresh.programmingLanguages()), join(fresh.spokenLanguages()),
                        join(fresh.mentorshipSignals()), target.rawJson());
                requirementUpdates++;
            }
        }
        requirements.flush();
        scores.flush();
        return ScoreRescoreWriteResult.success(targets.size(), requirementUpdates, ids, scoredAt);
    }

    private Map<Long, JobScore> scoreMap(List<JobScore> rows) {
        Map<Long, JobScore> result = new HashMap<>();
        for (JobScore row : rows) {
            Long id = row == null || row.getJob() == null ? null : row.getJob().getId();
            if (id == null || result.put(id, row) != null) {
                throw abort("Locked score targets are invalid or duplicated");
            }
        }
        return result;
    }

    private Map<Long, JobRequirement> requirementMap(List<JobRequirement> rows) {
        Map<Long, JobRequirement> result = new HashMap<>();
        for (JobRequirement row : rows) {
            Long id = row == null || row.getJob() == null ? null : row.getJob().getId();
            if (id == null || result.put(id, row) != null) {
                throw abort("Locked requirements targets are invalid or duplicated");
            }
        }
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw abort("Fresh requirements could not be serialized");
        }
    }

    private String join(List<String> values) {
        return String.join("|", values);
    }

    private IllegalStateException abort(String safeMessage) {
        return new IllegalStateException(safeMessage);
    }

    private record LockedTarget(JobScore score, JobRequirement requirement,
                                ScoreCalculation computed, String rawJson) {
        private LockedTarget {
            Objects.requireNonNull(score);
            Objects.requireNonNull(requirement);
            Objects.requireNonNull(computed);
            Objects.requireNonNull(rawJson);
        }
    }
}
