package com.jobpilot.sources.cleanup;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jobpilot.matching.preview.ScoreRescorePreviewProperties;
import com.jobpilot.matching.rescore.ScoreRescoreCommandProperties;
import com.jobpilot.support.TestProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class SourceLogCleanupCommandRunnerTest {
    @Test
    void offModePerformsNoCleanupSpecificReadOrRendering() {
        SourceLogCleanupPreviewService preview = mock(SourceLogCleanupPreviewService.class);
        SourceLogCleanupPreviewRenderer renderer = mock(SourceLogCleanupPreviewRenderer.class);
        SourceLogCleanupWriteCoordinator writer = mock(SourceLogCleanupWriteCoordinator.class);
        SourceLogCleanupCommandRunner runner = runner(new SourceLogCleanupProperties(
                null, null, null, null, null), preview, renderer, writer,
                new MockEnvironment());

        runner.run(mock(ApplicationArguments.class));

        verifyNoInteractions(preview, renderer, writer);
    }

    @Test
    void previewRefusesToReadWhileSchedulingIsEnabled() {
        SourceLogCleanupPreviewService preview = mock(SourceLogCleanupPreviewService.class);
        SourceLogCleanupPreviewRenderer renderer = mock(SourceLogCleanupPreviewRenderer.class);
        SourceLogCleanupWriteCoordinator writer = mock(SourceLogCleanupWriteCoordinator.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("jobpilot.scheduled-tasks-enabled", "true");
        SourceLogCleanupCommandRunner runner = runner(new SourceLogCleanupProperties(
                SourceLogCleanupProperties.Mode.PREVIEW, Duration.ofHours(6), 20,
                "69", "1"), preview, renderer, writer, environment);

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("One-shot source-log cleanup command failed");
        verifyNoInteractions(preview, renderer, writer);
    }

    private SourceLogCleanupCommandRunner runner(
            SourceLogCleanupProperties properties,
            SourceLogCleanupPreviewService preview,
            SourceLogCleanupPreviewRenderer renderer,
            SourceLogCleanupWriteCoordinator writer,
            MockEnvironment environment) {
        return new SourceLogCleanupCommandRunner(properties, preview, renderer, writer,
                TestProperties.create(), new ScoreRescorePreviewProperties(false, 250),
                new ScoreRescoreCommandProperties(null, false, null, null, null, null),
                environment);
    }
}
