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

/** Dedicated no-web, no-scheduler one-shot preview/write command. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SourceLogCleanupCommandRunner implements ApplicationRunner {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(SourceLogCleanupCommandRunner.class);

    private final SourceLogCleanupProperties properties;
    private final SourceLogCleanupPreviewService preview;
    private final SourceLogCleanupPreviewRenderer renderer;
    private final SourceLogCleanupWriteCoordinator writer;
    private final JobPilotProperties jobPilot;
    private final ScoreRescorePreviewProperties scoreStartupPreview;
    private final ScoreRescoreCommandProperties scoreCommand;
    private final Environment environment;

    public SourceLogCleanupCommandRunner(
            SourceLogCleanupProperties properties,
            SourceLogCleanupPreviewService preview,
            SourceLogCleanupPreviewRenderer renderer,
            SourceLogCleanupWriteCoordinator writer,
            JobPilotProperties jobPilot,
            ScoreRescorePreviewProperties scoreStartupPreview,
            ScoreRescoreCommandProperties scoreCommand,
            Environment environment) {
        this.properties = properties;
        this.preview = preview;
        this.renderer = renderer;
        this.writer = writer;
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
            if (!plan.previewSafe()) fail("PREVIEW_EVIDENCE_BLOCKED");
            if (properties.mode() == SourceLogCleanupProperties.Mode.WRITE) {
                SourceLogCleanupWriteResult result = writer.execute(plan, properties);
                if (result.status() == SourceLogCleanupWriteResult.Status.ERROR) {
                    fail(result.safeReason());
                }
                LOGGER.info("SOURCE_LOG_CLEANUP_WRITE success rowsUpdated={} ids={} "
                                + "finishedAt={}", result.rowsUpdated(), result.ids(),
                        result.finishedAt());
            }
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            LOGGER.info("SOURCE_LOG_CLEANUP_COMMAND complete mode={} durationMs={}",
                    properties.mode(), durationMillis);
        } catch (CommandFailure safeFailure) {
            LOGGER.error("SOURCE_LOG_CLEANUP_COMMAND failed mode={} reason={}",
                    properties.mode(),
                    safeFailure.getMessage());
            throw new IllegalStateException("One-shot source-log cleanup command failed");
        } catch (IllegalArgumentException invalidConfiguration) {
            LOGGER.error("SOURCE_LOG_CLEANUP_COMMAND failed mode={} "
                    + "reason=INVALID_CONFIGURATION", properties.mode());
            throw new IllegalStateException("One-shot source-log cleanup command failed");
        } catch (RuntimeException previewFailure) {
            LOGGER.error("SOURCE_LOG_CLEANUP_COMMAND failed mode={} "
                    + "reason=COMMAND_EXECUTION_FAILED", properties.mode());
            throw new IllegalStateException("One-shot source-log cleanup command failed");
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
