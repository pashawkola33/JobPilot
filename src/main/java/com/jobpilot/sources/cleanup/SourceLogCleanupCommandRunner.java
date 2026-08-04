package com.jobpilot.sources.cleanup;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.matching.preview.ScoreRescorePreviewProperties;
import com.jobpilot.matching.rescore.ScoreRescoreCommandProperties;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Dedicated no-web, no-scheduler, read-only one-shot command. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SourceLogCleanupCommandRunner implements ApplicationRunner {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(SourceLogCleanupCommandRunner.class);

    private final SourceLogCleanupProperties properties;
    private final SourceLogCleanupPreviewService preview;
    private final SourceLogCleanupPreviewRenderer renderer;
    private final JobPilotProperties jobPilot;
    private final ScoreRescorePreviewProperties scoreStartupPreview;
    private final ScoreRescoreCommandProperties scoreCommand;
    private final Environment environment;

    public SourceLogCleanupCommandRunner(
            SourceLogCleanupProperties properties,
            SourceLogCleanupPreviewService preview,
            SourceLogCleanupPreviewRenderer renderer,
            JobPilotProperties jobPilot,
            ScoreRescorePreviewProperties scoreStartupPreview,
            ScoreRescoreCommandProperties scoreCommand,
            Environment environment) {
        this.properties = properties;
        this.preview = preview;
        this.renderer = renderer;
        this.jobPilot = jobPilot;
        this.scoreStartupPreview = scoreStartupPreview;
        this.scoreCommand = scoreCommand;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (properties.mode() == SourceLogCleanupProperties.Mode.OFF) return;
        long started = System.nanoTime();
        try {
            validateProcessBoundary();
            SourceLogCleanupPlan plan = preview.plan(properties.guards());
            renderer.render(plan).forEach(line -> LOGGER.info("{}", line));
            if (!plan.futureWriteEligible()) fail("PREVIEW_EVIDENCE_BLOCKED");
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            LOGGER.info("SOURCE_LOG_CLEANUP_COMMAND complete mode=PREVIEW durationMs={}",
                    durationMillis);
        } catch (CommandFailure safeFailure) {
            LOGGER.error("SOURCE_LOG_CLEANUP_COMMAND failed mode=PREVIEW reason={}",
                    safeFailure.getMessage());
            throw new IllegalStateException("One-shot source-log cleanup preview failed");
        } catch (IllegalArgumentException invalidConfiguration) {
            LOGGER.error("SOURCE_LOG_CLEANUP_COMMAND failed mode=PREVIEW "
                    + "reason=INVALID_CONFIGURATION");
            throw new IllegalStateException("One-shot source-log cleanup preview failed");
        } catch (RuntimeException previewFailure) {
            LOGGER.error("SOURCE_LOG_CLEANUP_COMMAND failed mode=PREVIEW "
                    + "reason=PREVIEW_CONSTRUCTION_FAILED");
            throw new IllegalStateException("One-shot source-log cleanup preview failed");
        }
    }

    private void validateProcessBoundary() {
        String webType = environment.getProperty("spring.main.web-application-type", "");
        if (!"none".equals(webType.toLowerCase(Locale.ROOT))) {
            fail("WEB_APPLICATION_MUST_BE_DISABLED");
        }
        boolean schedulingEnabled = environment.getProperty(
                "jobpilot.scheduled-tasks-enabled", Boolean.class, true);
        if (schedulingEnabled) fail("SCHEDULED_TASKS_MUST_BE_DISABLED");
        if (jobPilot.telegram().pollingEnabled()) fail("TELEGRAM_POLLING_MUST_BE_DISABLED");
        if (scoreStartupPreview.enabled()) fail("SCORE_STARTUP_PREVIEW_MUST_BE_DISABLED");
        if (scoreCommand.mode() != ScoreRescoreCommandProperties.Mode.OFF) {
            fail("SCORE_COMMAND_MUST_BE_OFF");
        }
    }

    private void fail(String safeReason) {
        throw new CommandFailure(safeReason);
    }

    private static final class CommandFailure extends RuntimeException {
        private CommandFailure(String safeReason) {
            super(safeReason);
        }
    }
}
