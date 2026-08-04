package com.jobpilot.matching.preview;

import com.jobpilot.jobreview.domain.JobWorkflowState;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobreview.repository.JobWorkflowStateRepository;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobRequirement;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.jobs.domain.JobStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.matching.JobScoreCalculator;
import com.jobpilot.matching.ScoreCalculation;
import com.jobpilot.matching.ScoreCard;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.BoundaryCrossings;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.JobPreview;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.QueueEntry;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.QueueProjection;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.ScoreSnapshot;
import com.jobpilot.matching.preview.ScoreRescorePreviewResult.ErrorCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One repeatable-read calculation pass over persisted MATCH/REVIEW score rows.
 * Its dependency graph contains read repositories and the pure production calculator only.
 */
@Service
public class ScoreRescorePreviewService {
    static final String TARGET_TITLE = "Code First Girls Programme - Junior Java Developer";

    private final JobScoreRepository scores;
    private final JobRequirementRepository requirements;
    private final JobWorkflowStateRepository workflowStates;
    private final JobScoreCalculator calculator;

    public ScoreRescorePreviewService(JobScoreRepository scores,
                                      JobRequirementRepository requirements,
                                      JobWorkflowStateRepository workflowStates,
                                      JobScoreCalculator calculator) {
        this.scores = scores;
        this.requirements = requirements;
        this.workflowStates = workflowStates;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScoreRescorePreviewResult preview(int maximumJobs) {
        if (maximumJobs < 1
                || maximumJobs > ScoreRescorePreviewProperties.HARD_MAX_INSPECTED_JOBS) {
            return ScoreRescorePreviewResult.error(ErrorCategory.INVALID_LIMIT,
                    "Configured preview limit is outside the safe range");
        }
        try {
            long candidateCount = scores.countRescorePreviewCandidates();
            if (candidateCount > maximumJobs) {
                return ScoreRescorePreviewResult.error(ErrorCategory.CAP_EXCEEDED,
                        "Preview candidate count " + candidateCount
                                + " exceeds configured limit " + maximumJobs);
            }
            if (candidateCount == 0) return ScoreRescorePreviewResult.success(emptyReport());

            List<JobScore> scoreRows = scores.findRescorePreviewCandidates(
                    PageRequest.of(0, maximumJobs));
            if (scoreRows.size() != candidateCount) {
                throw abort(ErrorCategory.INCONSISTENT_PERSISTED_DATA,
                        "Preview snapshot did not return its counted candidate rows");
            }

            List<Long> ids = new ArrayList<>(scoreRows.size());
            for (JobScore scoreRow : scoreRows) {
                Job job = requireJob(scoreRow);
                ids.add(job.getId());
            }
            if (new HashSet<>(ids).size() != ids.size()) {
                throw abort(ErrorCategory.INCONSISTENT_PERSISTED_DATA,
                        "Preview snapshot contains duplicate job identifiers");
            }

            Map<Long, JobRequirement> requirementByJob = requirementMap(
                    requirements.findAllByJobIdIn(ids));
            Map<Long, WorkflowStatus> workflowByJob = workflowMap(
                    workflowStates.findAllByJobIdIn(ids));

            List<EvaluatedJob> evaluated = new ArrayList<>(scoreRows.size());
            for (JobScore scoreRow : scoreRows) {
                Job job = scoreRow.getJob();
                validateRequiredJobData(job);
                if (job.getScreeningDisposition() == ScreeningDisposition.REJECT) {
                    throw abort(ErrorCategory.INCONSISTENT_PERSISTED_DATA,
                            "REJECT job reached preview snapshot for job " + job.getId());
                }
                JobRequirement requirementRow = requirementByJob.get(job.getId());
                if (requirementRow == null) {
                    throw missing(job.getId(), "job_requirements row");
                }
                ExtractedRequirements storedRequirements = requirementRow.toValue();
                validateRequirements(job.getId(), storedRequirements);
                ScoreCard storedScore = scoreRow.toValue();
                if (storedScore.band() == null) throw missing(job.getId(), "stored score band");

                ScoreCalculation computed;
                try {
                    computed = calculator.calculate(job);
                } catch (RuntimeException calculationFailure) {
                    throw abort(ErrorCategory.CALCULATION_FAILED,
                            "Current score calculation failed for job " + job.getId());
                }
                boolean seniorityFix = causedBySeniorityFix(job, storedRequirements,
                        storedScore, computed);
                evaluated.add(new EvaluatedJob(job,
                        workflowByJob.getOrDefault(job.getId(), WorkflowStatus.UNREVIEWED),
                        storedRequirements, storedScore, computed, seniorityFix));
            }

            return ScoreRescorePreviewResult.success(buildReport(evaluated));
        } catch (PreviewAbort failure) {
            return ScoreRescorePreviewResult.error(failure.category, failure.getMessage());
        }
    }

    private boolean causedBySeniorityFix(Job job, ExtractedRequirements storedRequirements,
                                         ScoreCard storedScore, ScoreCalculation computed) {
        String oldSeniority = storedRequirements.seniority();
        String newSeniority = computed.requirements().seniority();
        if (Objects.equals(oldSeniority, newSeniority)) return false;
        ExtractedRequirements counterfactual = withSeniority(computed.requirements(), oldSeniority);
        ScoreCard withoutFix;
        try {
            withoutFix = calculator.calculate(job, counterfactual).score();
        } catch (RuntimeException calculationFailure) {
            throw abort(ErrorCategory.CALCULATION_FAILED,
                    "Seniority counterfactual failed for job " + job.getId());
        }
        return sameOutcome(storedScore, withoutFix)
                && !sameOutcome(withoutFix, computed.score());
    }

    private boolean sameOutcome(ScoreCard left, ScoreCard right) {
        return left.score() == right.score()
                && left.band() == right.band()
                && left.suitable() == right.suitable()
                && left.penalties() == right.penalties()
                && new HashSet<>(left.hardBlockers()).equals(new HashSet<>(right.hardBlockers()));
    }

    private ExtractedRequirements withSeniority(ExtractedRequirements value, String seniority) {
        return new ExtractedRequirements(seniority, value.internshipOrTrainee(),
                value.requiredExperienceYears(), value.requiredEducation(),
                value.finalYearMandatory(), value.technologies(), value.programmingLanguages(),
                value.spokenLanguages(), value.location(), value.remoteEligibility(),
                value.mentorshipSignals(), value.workAuthorization(), value.salary(),
                value.applicationDeadline(), value.extractionMethod());
    }

    private ScoreRescorePreviewReport buildReport(List<EvaluatedJob> jobs) {
        QueueProjection matches = queueProjection(jobs, ScreeningDisposition.MATCH);
        QueueProjection review = queueProjection(jobs, ScreeningDisposition.REVIEW);
        Map<Long, Integer> oldPositions = positions(matches.before(), review.before());
        Map<Long, Integer> newPositions = positions(matches.after(), review.after());

        int exact = 0;
        int changedScore = 0;
        int changedBand = 0;
        int blockerRemoved = 0;
        int blockerAdded = 0;
        int increases = 0;
        int decreases = 0;
        int zeroToPositive = 0;
        int positiveToZero = 0;
        Map<Integer, Integer> deltas = new TreeMap<>();
        List<Long> unsuitableCrossings = new ArrayList<>();
        List<Long> possibleCrossings = new ArrayList<>();
        List<Long> strongCrossings = new ArrayList<>();
        List<JobPreview> changes = new ArrayList<>();
        JobPreview named = null;

        for (EvaluatedJob evaluated : jobs) {
            ScoreCard oldScore = evaluated.storedScore();
            ScoreCard newScore = evaluated.computed().score();
            int delta = newScore.score() - oldScore.score();
            deltas.merge(delta, 1, Integer::sum);
            if (oldScore.equals(newScore)) exact++;
            if (delta != 0) changedScore++;
            if (oldScore.band() != newScore.band()) changedBand++;
            if (removed(oldScore.hardBlockers(), newScore.hardBlockers())) blockerRemoved++;
            if (removed(newScore.hardBlockers(), oldScore.hardBlockers())) blockerAdded++;
            if (delta > 0) increases++;
            if (delta < 0) decreases++;
            if (oldScore.score() == 0 && newScore.score() > 0) zeroToPositive++;
            if (oldScore.score() > 0 && newScore.score() == 0) positiveToZero++;
            if ((oldScore.band() == ScoreBand.UNSUITABLE)
                    != (newScore.band() == ScoreBand.UNSUITABLE)) {
                unsuitableCrossings.add(evaluated.job().getId());
            }
            if (crosses(oldScore.score(), newScore.score(), 55)) {
                possibleCrossings.add(evaluated.job().getId());
            }
            if (crosses(oldScore.score(), newScore.score(), 70)) {
                strongCrossings.add(evaluated.job().getId());
            }
            JobPreview preview = preview(evaluated, oldPositions, newPositions);
            if (!oldScore.equals(newScore)) changes.add(preview);
            if (targetTitle(evaluated.job().getTitle())) named = preview;
        }

        LinkedHashMap<Integer, Integer> orderedDeltas = new LinkedHashMap<>();
        deltas.forEach(orderedDeltas::put);
        return new ScoreRescorePreviewReport(jobs.size(), exact, changedScore, changedBand,
                blockerRemoved, blockerAdded, increases, decreases,
                Collections.unmodifiableMap(orderedDeltas), zeroToPositive, positiveToZero,
                new BoundaryCrossings(List.copyOf(unsuitableCrossings),
                        List.copyOf(possibleCrossings), List.copyOf(strongCrossings)),
                staleMatchInversions(jobs), matches, review, List.copyOf(changes), named);
    }

    private JobPreview preview(EvaluatedJob evaluated, Map<Long, Integer> oldPositions,
                               Map<Long, Integer> newPositions) {
        long id = evaluated.job().getId();
        Integer oldPosition = oldPositions.get(id);
        Integer newPosition = newPositions.get(id);
        return new JobPreview(id,
                PreviewTextSanitizer.text(evaluated.job().getTitle(), 120),
                PreviewTextSanitizer.text(evaluated.job().getSource(), 40),
                PreviewTextSanitizer.text(evaluated.job().getProviderTenant(), 80),
                PreviewTextSanitizer.externalId(evaluated.job().getExternalId()),
                evaluated.job().getScreeningDisposition(), evaluated.workflowStatus(),
                snapshot(evaluated.storedScore(), evaluated.storedRequirements().seniority()),
                snapshot(evaluated.computed().score(),
                        evaluated.computed().requirements().seniority()),
                evaluated.computed().score().score() - evaluated.storedScore().score(),
                evaluated.computed().rawComponentTotal(), evaluated.causedBySeniorityFix(),
                !Objects.equals(oldPosition, newPosition), oldPosition, newPosition);
    }

    private ScoreSnapshot snapshot(ScoreCard score, String seniority) {
        return new ScoreSnapshot(score.score(), score.band(), score.penalties(),
                PreviewTextSanitizer.list(score.risks()),
                PreviewTextSanitizer.list(score.hardBlockers()),
                PreviewTextSanitizer.text(seniority, 30));
    }

    private QueueProjection queueProjection(List<EvaluatedJob> jobs,
                                            ScreeningDisposition disposition) {
        List<EvaluatedJob> visible = jobs.stream()
                .filter(job -> job.job().getScreeningDisposition() == disposition)
                .filter(job -> job.job().getStatus() != JobStatus.EXPIRED)
                .filter(job -> job.workflowStatus() != WorkflowStatus.DISMISSED)
                .toList();
        return new QueueProjection(queueEntries(visible, false), queueEntries(visible, true));
    }

    private List<QueueEntry> queueEntries(List<EvaluatedJob> jobs, boolean computed) {
        List<EvaluatedJob> ordered = new ArrayList<>(jobs);
        ordered.sort(queueComparator(computed));
        List<QueueEntry> result = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            EvaluatedJob value = ordered.get(index);
            int score = computed ? value.computed().score().score() : value.storedScore().score();
            result.add(new QueueEntry(index + 1, value.job().getId(),
                    PreviewTextSanitizer.text(value.job().getTitle(), 120), score,
                    value.workflowStatus()));
        }
        return List.copyOf(result);
    }

    private Comparator<EvaluatedJob> queueComparator(boolean computed) {
        return Comparator.comparingInt((EvaluatedJob value) -> workflowRank(value.workflowStatus()))
                .thenComparing((left, right) -> Integer.compare(
                        score(right, computed), score(left, computed)))
                .thenComparing((left, right) -> recency(right).compareTo(recency(left)))
                .thenComparing((left, right) -> Long.compare(
                        right.job().getId(), left.job().getId()));
    }

    private int score(EvaluatedJob value, boolean computed) {
        return computed ? value.computed().score().score() : value.storedScore().score();
    }

    private Instant recency(EvaluatedJob value) {
        return value.job().getPublishedAt() == null
                ? value.job().getFirstSeenAt() : value.job().getPublishedAt();
    }

    private int workflowRank(WorkflowStatus status) {
        return switch (status) {
            case UNREVIEWED -> 0;
            case SAVED -> 1;
            case APPLIED, DISMISSED -> 2;
        };
    }

    private Map<Long, Integer> positions(List<QueueEntry> first, List<QueueEntry> second) {
        Map<Long, Integer> result = new HashMap<>();
        first.forEach(entry -> result.put(entry.jobId(), entry.position()));
        second.forEach(entry -> result.put(entry.jobId(), entry.position()));
        return result;
    }

    private List<Long> staleMatchInversions(List<EvaluatedJob> jobs) {
        List<EvaluatedJob> reviews = jobs.stream()
                .filter(job -> job.job().getScreeningDisposition() == ScreeningDisposition.REVIEW)
                .toList();
        List<Long> result = new ArrayList<>();
        for (EvaluatedJob match : jobs) {
            if (match.job().getScreeningDisposition() != ScreeningDisposition.MATCH) continue;
            boolean inverted = reviews.stream().anyMatch(review ->
                    match.storedScore().score() < review.storedScore().score()
                            && match.computed().score().score() > review.computed().score().score());
            if (inverted) result.add(match.job().getId());
        }
        return List.copyOf(result);
    }

    private Map<Long, JobRequirement> requirementMap(List<JobRequirement> rows) {
        Map<Long, JobRequirement> result = new HashMap<>();
        for (JobRequirement row : rows) {
            if (row == null || row.getJob() == null || row.getJob().getId() == null) {
                throw abort(ErrorCategory.INCONSISTENT_PERSISTED_DATA,
                        "Preview encountered an unassociated requirements row");
            }
            if (result.put(row.getJob().getId(), row) != null) {
                throw abort(ErrorCategory.INCONSISTENT_PERSISTED_DATA,
                        "Preview encountered duplicate requirements rows");
            }
        }
        return result;
    }

    private Map<Long, WorkflowStatus> workflowMap(List<JobWorkflowState> rows) {
        Map<Long, WorkflowStatus> result = new HashMap<>();
        for (JobWorkflowState row : rows) {
            if (row == null || row.getJobId() == null || row.getStatus() == null) {
                throw abort(ErrorCategory.INCONSISTENT_PERSISTED_DATA,
                        "Preview encountered an invalid workflow row");
            }
            if (result.put(row.getJobId(), row.getStatus()) != null) {
                throw abort(ErrorCategory.INCONSISTENT_PERSISTED_DATA,
                        "Preview encountered duplicate workflow rows");
            }
        }
        return result;
    }

    private Job requireJob(JobScore scoreRow) {
        if (scoreRow == null || scoreRow.getJob() == null) {
            throw abort(ErrorCategory.INCONSISTENT_PERSISTED_DATA,
                    "Preview encountered an unassociated score row");
        }
        if (scoreRow.getJob().getId() == null) {
            throw abort(ErrorCategory.MISSING_REQUIRED_DATA,
                    "Preview score row has no job identifier");
        }
        return scoreRow.getJob();
    }

    private void validateRequiredJobData(Job job) {
        require(job, job.getSource(), "source");
        require(job, job.getProviderTenant(), "provider_tenant");
        require(job, job.getTitle(), "title");
        require(job, job.getDescription(), "description");
        if (job.getRemoteType() == null) throw missing(job.getId(), "remote_type");
        if (job.getLocationEligibility() == null) {
            throw missing(job.getId(), "location_eligibility");
        }
        if (job.getFirstSeenAt() == null) throw missing(job.getId(), "first_seen_at");
        if (job.getStatus() == null) throw missing(job.getId(), "status");
        if (job.getScreeningDisposition() == null) {
            throw missing(job.getId(), "screening_disposition");
        }
    }

    private void validateRequirements(long jobId, ExtractedRequirements value) {
        if (value == null) throw missing(jobId, "persisted requirements");
        if (value.seniority() == null || value.seniority().isBlank()) {
            throw missing(jobId, "persisted inferred seniority");
        }
        if (value.extractionMethod() == null || value.extractionMethod().isBlank()) {
            throw missing(jobId, "persisted extraction method");
        }
        if (value.technologies() == null || value.programmingLanguages() == null
                || value.spokenLanguages() == null || value.mentorshipSignals() == null) {
            throw missing(jobId, "persisted requirement collection");
        }
    }

    private void require(Job job, String value, String field) {
        if (value == null || value.isBlank()) throw missing(job.getId(), field);
    }

    private PreviewAbort missing(long jobId, String field) {
        return abort(ErrorCategory.MISSING_REQUIRED_DATA,
                "Required persisted " + field + " is missing for job " + jobId);
    }

    private boolean removed(List<String> before, List<String> after) {
        Set<String> difference = new HashSet<>(before);
        difference.removeAll(new HashSet<>(after));
        return !difference.isEmpty();
    }

    private boolean crosses(int before, int after, int threshold) {
        return before < threshold && after >= threshold
                || before >= threshold && after < threshold;
    }

    private boolean targetTitle(String title) {
        if (title == null) return false;
        String normalized = title.replace('\u2013', '-').replace('\u2014', '-')
                .replaceAll("\\s+", " ").strip();
        return TARGET_TITLE.equalsIgnoreCase(normalized);
    }

    private ScoreRescorePreviewReport emptyReport() {
        QueueProjection emptyQueue = new QueueProjection(List.of(), List.of());
        return new ScoreRescorePreviewReport(0, 0, 0, 0, 0, 0, 0, 0, Map.of(),
                0, 0, new BoundaryCrossings(List.of(), List.of(), List.of()), List.of(),
                emptyQueue, emptyQueue, List.of(), null);
    }

    private PreviewAbort abort(ErrorCategory category, String safeMessage) {
        return new PreviewAbort(category, safeMessage);
    }

    private record EvaluatedJob(
            Job job,
            WorkflowStatus workflowStatus,
            ExtractedRequirements storedRequirements,
            ScoreCard storedScore,
            ScoreCalculation computed,
            boolean causedBySeniorityFix) {
    }

    private static final class PreviewAbort extends RuntimeException {
        private final ErrorCategory category;

        private PreviewAbort(ErrorCategory category, String safeMessage) {
            super(safeMessage);
            this.category = category;
        }
    }
}
