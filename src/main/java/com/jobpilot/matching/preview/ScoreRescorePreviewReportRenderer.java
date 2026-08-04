package com.jobpilot.matching.preview;

import com.jobpilot.common.Utf16;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.JobPreview;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.QueueEntry;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.QueueProjection;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Produces bounded one-line log records without descriptions, URLs, or unbounded text. */
@Component
public class ScoreRescorePreviewReportRenderer {
    static final int MAX_LINE_LENGTH = 3_500;

    public List<String> render(ScoreRescorePreviewResult result) {
        if (result.status() == ScoreRescorePreviewResult.Status.ERROR) {
            return List.of(bound("SCORE_RESCORE_PREVIEW status=ERROR category="
                    + result.errorCategory() + " message=" + quote(
                    PreviewTextSanitizer.text(result.safeMessage(), 240))));
        }

        ScoreRescorePreviewReport report = result.report();
        List<String> lines = new ArrayList<>();
        lines.add("SCORE_RESCORE_PREVIEW status=SUCCESS inspected=" + report.inspectedJobs()
                + " exact=" + report.exactMatches()
                + " changedScores=" + report.changedScoreCount()
                + " changedBands=" + report.changedBandCount()
                + " blockerRemoved=" + report.blockerRemovedCount()
                + " blockerAdded=" + report.blockerAddedCount()
                + " increases=" + report.scoreIncreaseCount()
                + " decreases=" + report.scoreDecreaseCount()
                + " zeroToPositive=" + report.storedZeroToPositiveCount()
                + " positiveToZero=" + report.storedPositiveToZeroCount());
        lines.add(bound("SCORE_RESCORE_PREVIEW_DELTA distribution="
                + report.scoreDeltaDistribution()));
        lines.add(bound("SCORE_RESCORE_PREVIEW_BOUNDARIES unsuitable="
                + report.boundaryCrossings().unsuitable()
                + " possibleMatch55=" + report.boundaryCrossings().possibleMatch()
                + " strongMatch70=" + report.boundaryCrossings().strongMatch()));
        lines.add(bound("SCORE_RESCORE_PREVIEW_MATCH_BELOW_REVIEW jobs="
                + report.matchJobsBelowReviewBecauseOfStaleScores()));
        for (JobPreview job : report.changedJobs()) {
            lines.add(renderJob("SCORE_RESCORE_PREVIEW_CHANGED", job));
        }
        renderQueue(lines, "matches", report.matchesQueue());
        renderQueue(lines, "review", report.reviewQueue());
        if (report.juniorJavaDeveloper() == null) {
            lines.add("SCORE_RESCORE_PREVIEW_TARGET status=NOT_FOUND");
        } else {
            lines.add(renderJob("SCORE_RESCORE_PREVIEW_TARGET", report.juniorJavaDeveloper()));
        }
        lines.add("SCORE_RESCORE_PREVIEW_COMPLETE readOnly=true writeBackExecuted=false");
        return List.copyOf(lines);
    }

    private String renderJob(String prefix, JobPreview job) {
        return bound(prefix
                + " jobId=" + job.jobId()
                + " title=" + quote(job.title())
                + " source=" + quote(job.source())
                + " providerTenant=" + quote(job.providerTenant())
                + " externalId=" + quote(job.externalId())
                + " queue=" + job.workflowQueue()
                + " workflow=" + job.workflowStatus()
                + " stored=" + job.stored().score() + "/" + job.stored().band()
                + " computed=" + job.computed().score() + "/" + job.computed().band()
                + " delta=" + signed(job.delta())
                + " storedBlockers=" + job.stored().blockers()
                + " computedBlockers=" + job.computed().blockers()
                + " storedPenalties=" + job.stored().penalties()
                + " storedPenaltyReasons=" + job.stored().penaltyReasons()
                + " computedPenalties=" + job.computed().penalties()
                + " computedPenaltyReasons=" + job.computed().penaltyReasons()
                + " storedSeniority=" + job.stored().inferredSeniority()
                + " computedSeniority=" + job.computed().inferredSeniority()
                + " rawComponentTotal=" + job.rawComponentTotal()
                + " seniorityExtractorFix=" + job.causedBySeniorityExtractorFix()
                + " queuePositionChanged=" + job.telegramQueuePositionChanged()
                + " queuePosition=" + position(job.oldQueuePosition())
                + "->" + position(job.newQueuePosition()));
    }

    private void renderQueue(List<String> lines, String name, QueueProjection queue) {
        lines.add("SCORE_RESCORE_PREVIEW_QUEUE queue=/" + name
                + " phase=BEFORE total=" + queue.before().size());
        queue.before().forEach(entry -> lines.add(queueEntry(name, "BEFORE", entry)));
        lines.add("SCORE_RESCORE_PREVIEW_QUEUE queue=/" + name
                + " phase=AFTER total=" + queue.after().size());
        queue.after().forEach(entry -> lines.add(queueEntry(name, "AFTER", entry)));
    }

    private String queueEntry(String name, String phase, QueueEntry entry) {
        return bound("SCORE_RESCORE_PREVIEW_QUEUE_ITEM queue=/" + name
                + " phase=" + phase + " position=" + entry.position()
                + " jobId=" + entry.jobId() + " score=" + entry.score()
                + " workflow=" + entry.workflowStatus()
                + " title=" + quote(entry.title()));
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String position(Integer value) {
        return value == null ? "OUT" : value.toString();
    }

    private String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private String bound(String line) {
        return Utf16.truncate(line, MAX_LINE_LENGTH);
    }
}
