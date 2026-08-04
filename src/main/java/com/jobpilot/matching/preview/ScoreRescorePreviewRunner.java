package com.jobpilot.matching.preview;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Runs exactly once during startup, and is inert before its explicit opt-in check. */
@Component
public class ScoreRescorePreviewRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoreRescorePreviewRunner.class);

    private final ScoreRescorePreviewProperties properties;
    private final ScoreRescorePreviewService preview;
    private final ScoreRescorePreviewReportRenderer renderer;

    public ScoreRescorePreviewRunner(ScoreRescorePreviewProperties properties,
                                     ScoreRescorePreviewService preview,
                                     ScoreRescorePreviewReportRenderer renderer) {
        this.properties = properties;
        this.preview = preview;
        this.renderer = renderer;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled()) return;
        List<String> lines;
        try {
            lines = renderer.render(preview.preview(properties.maxInspectedJobs()));
        } catch (RuntimeException unexpectedFailure) {
            lines = renderer.render(ScoreRescorePreviewResult.error(
                    ScoreRescorePreviewResult.ErrorCategory.INTERNAL_ERROR,
                    "Preview stopped after an unexpected internal error"));
        }
        lines.forEach(line -> LOGGER.info("{}", line));
    }
}
