package com.jobpilot.matching.rescore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jobpilot.matching.preview.ScoreRescorePreviewProperties;
import com.jobpilot.matching.preview.ScoreRescorePreviewReportRenderer;
import com.jobpilot.matching.preview.ScoreRescorePreviewService;
import com.jobpilot.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class ScoreRescoreCommandRunnerTest {
    @Test
    void offModeIsInert() {
        ScoreRescorePreviewService planner = mock(ScoreRescorePreviewService.class);
        ScoreRescoreWriteCoordinator writer = mock(ScoreRescoreWriteCoordinator.class);
        ScoreRescoreCommandRunner runner = runner(new ScoreRescoreCommandProperties(
                null, false, null, null, null, null), planner, writer,
                new MockEnvironment());

        runner.run(mock(ApplicationArguments.class));

        verifyNoInteractions(planner, writer);
    }

    @Test
    void commandRefusesToPlanWhileScheduledTasksAreEnabled() {
        ScoreRescorePreviewService planner = mock(ScoreRescorePreviewService.class);
        ScoreRescoreWriteCoordinator writer = mock(ScoreRescoreWriteCoordinator.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("jobpilot.scheduled-tasks-enabled", "true");
        ScoreRescoreCommandRunner runner = runner(new ScoreRescoreCommandProperties(
                ScoreRescoreCommandProperties.Mode.PREVIEW, false,
                null, null, "250", null), planner, writer, environment);

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("One-shot score rescore command failed");
        verifyNoInteractions(planner, writer);
    }

    private ScoreRescoreCommandRunner runner(ScoreRescoreCommandProperties properties,
                                             ScoreRescorePreviewService planner,
                                             ScoreRescoreWriteCoordinator writer,
                                             MockEnvironment environment) {
        return new ScoreRescoreCommandRunner(properties,
                new ScoreRescorePreviewProperties(false, 250), TestProperties.create(),
                mock(ScoreRescorePreviewReportRenderer.class), planner, writer, environment);
    }
}
