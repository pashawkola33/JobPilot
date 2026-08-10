package com.jobpilot.matching.preview;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.matching.preview.ScoreRescorePreviewReport.BoundaryCrossings;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.ChangeCounts;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.QueueProjection;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.RequirementField;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.RequirementFieldChange;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.RequirementsPreview;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rendered preview is what an operator approves a write against. */
class ScoreRescorePreviewReportRendererTest {
    private static final String DESCRIPTION =
            "Confidential posting body that must never reach an operational log";
    private static final String URL = "https://example.com/jobs/secret-posting";

    private final ScoreRescorePreviewReportRenderer renderer =
            new ScoreRescorePreviewReportRenderer();

    @Test
    void namesEveryChangedRequirementFieldWithBothValues() {
        List<String> lines = renderer.render(ScoreRescorePreviewResult.success(report()));

        String requirementsLine = lines.stream()
                .filter(line -> line.startsWith("SCORE_RESCORE_PREVIEW_REQUIREMENTS"))
                .findFirst().orElseThrow();

        assertThat(requirementsLine).contains("jobId=42", "scoreChanged=false", "score=56->56",
                "changedFields=2",
                "SENIORITY=\"JUNIOR\"->\"UNKNOWN\"",
                "TECHNOLOGIES=\"[Java, Go]\"->\"[Java]\"");
    }

    @Test
    void exposesThePlanCountsInTheHeader() {
        List<String> lines = renderer.render(ScoreRescorePreviewResult.success(report()));

        assertThat(lines).anySatisfy(line -> assertThat(line).contains(
                "SCORE_RESCORE_PREVIEW_PLAN scoreChanged=0", "requirementsChanged=1",
                "requirementsOnlyChanged=1", "changedPlan=1", "unchanged=2"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("scoreDeltaChanged=0"));
    }

    @Test
    void neverPrintsDescriptionsOrUrls() {
        List<String> lines = renderer.render(ScoreRescorePreviewResult.success(report()));

        assertThat(lines).noneSatisfy(line -> assertThat(line).contains(DESCRIPTION));
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains(URL));
    }

    @Test
    void keepsEveryLineWithinTheLineBound() {
        List<String> lines = renderer.render(ScoreRescorePreviewResult.success(report()));

        assertThat(lines).allSatisfy(line -> assertThat(line.length())
                .isLessThanOrEqualTo(ScoreRescorePreviewReportRenderer.MAX_LINE_LENGTH));
    }

    private ScoreRescorePreviewReport report() {
        QueueProjection queue = new QueueProjection(List.of(), List.of());
        RequirementsPreview requirements = new RequirementsPreview(42L, "Java Developer", false,
                56, 56, List.of(
                        new RequirementFieldChange(RequirementField.SENIORITY, "JUNIOR", "UNKNOWN"),
                        new RequirementFieldChange(RequirementField.TECHNOLOGIES,
                                "[Java, Go]", "[Java]")));
        return new ScoreRescorePreviewReport(3, 3, 0, 0, 0, 0, 0, 0, Map.of(), 0, 0,
                new BoundaryCrossings(List.of(), List.of(), List.of()), List.of(),
                queue, queue, List.of(), null,
                new ChangeCounts(0, 1, 1, 1, 2), List.of(requirements));
    }
}
