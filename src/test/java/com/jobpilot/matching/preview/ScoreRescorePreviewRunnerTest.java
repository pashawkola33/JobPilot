package com.jobpilot.matching.preview;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class ScoreRescorePreviewRunnerTest {
    @Test
    void disabledModePerformsNoPreviewRepositoryPathReadsOrWrites() throws Exception {
        ScoreRescorePreviewService preview = mock(ScoreRescorePreviewService.class);
        ScoreRescorePreviewReportRenderer renderer = mock(ScoreRescorePreviewReportRenderer.class);
        ScoreRescorePreviewRunner runner = new ScoreRescorePreviewRunner(
                new ScoreRescorePreviewProperties(false, null), preview, renderer);

        runner.run(mock(ApplicationArguments.class));

        verifyNoInteractions(preview, renderer);
    }
}
