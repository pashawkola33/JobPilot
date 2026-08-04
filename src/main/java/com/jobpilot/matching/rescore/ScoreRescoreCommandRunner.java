package com.jobpilot.matching.rescore;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.matching.preview.ScoreRescorePreviewProperties;
import com.jobpilot.matching.preview.ScoreRescorePreviewReportRenderer;
import com.jobpilot.matching.preview.ScoreRescorePreviewResult;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Dedicated no-web, no-scheduler command that closes its context after one preview/write. */
@Component
public class ScoreRescoreCommandRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoreRescoreCommandRunner.class);

    private final ScoreRescoreCommandProperties properties;
    private final ScoreRescorePreviewProperties startupPreview;
    private final JobPilotProperties jobPilot;
    private final ScoreRescorePreviewReportRenderer renderer;
    private final com.jobpilot.matching.preview.ScoreRescorePreviewService planner;
    private final ScoreRescoreWriteCoordinator writer;
    private final Environment environment;

    public ScoreRescoreCommandRunner(
            ScoreRescoreCommandProperties properties,
            ScoreRescorePreviewProperties startupPreview,
            JobPilotProperties jobPilot,
            ScoreRescorePreviewReportRenderer renderer,
            com.jobpilot.matching.preview.ScoreRescorePreviewService planner,
            ScoreRescoreWriteCoordinator writer,
            Environment environment) {
        this.properties = properties;
        this.startupPreview = startupPreview;
        this.jobPilot = jobPilot;
        this.renderer = renderer;
        this.planner = planner;
        this.writer = writer;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (properties.mode() == ScoreRescoreCommandProperties.Mode.OFF) return;
        long started = System.nanoTime();
        try {
            validateProcessBoundary();
            Integer maximumJobs = ScoreRescoreCommandGuards.maximumJobs(properties.maxJobs());
            if (maximumJobs == null) fail("Maximum jobs guard is missing or invalid");
            ScoreRescorePlanResult result = planner.plan(maximumJobs);
            if (result.status() == ScoreRescorePlanResult.Status.ERROR) {
                renderer.render(ScoreRescorePreviewResult.error(
                        result.errorCategory(), result.safeMessage()))
                        .forEach(line -> LOGGER.info("{}", line));
                fail("Fresh rescore plan could not be constructed");
            }
            ScoreRescorePlan plan = result.plan();
            renderer.render(ScoreRescorePreviewResult.success(plan.report()))
                    .forEach(line -> LOGGER.info("{}", line));
            LOGGER.info("SCORE_RESCORE_COMMAND plan changed={} fingerprint={}",
                    plan.changedCount(), plan.fingerprint());

            if (properties.mode() == ScoreRescoreCommandProperties.Mode.WRITE) {
                ScoreRescoreWriteResult written = writer.execute(plan, properties);
                if (written.status() == ScoreRescoreWriteResult.Status.ERROR) {
                    fail(written.safeMessage());
                }
                LOGGER.info("SCORE_RESCORE_COMMAND write success scoreRows={} requirementRows={} "
                                + "jobIds={} scoredAt={}", written.scoreRowsUpdated(),
                        written.requirementRowsUpdated(), written.jobIds(), written.scoredAt());
            }
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            LOGGER.info("SCORE_RESCORE_COMMAND complete mode={} durationMs={}",
                    properties.mode(), durationMillis);
        } catch (CommandFailure safeFailure) {
            LOGGER.error("SCORE_RESCORE_COMMAND failed mode={} reason={}",
                    properties.mode(), safeFailure.getMessage());
            throw new IllegalStateException("One-shot score rescore command failed");
        }
    }

    private void validateProcessBoundary() {
        String webType = environment.getProperty("spring.main.web-application-type", "");
        if (!"none".equals(webType.toLowerCase(Locale.ROOT))) {
            fail("One-shot command requires spring.main.web-application-type=none");
        }
        boolean schedulingEnabled = environment.getProperty(
                "jobpilot.scheduled-tasks-enabled", Boolean.class, true);
        if (schedulingEnabled) fail("One-shot command requires scheduled tasks to be disabled");
        if (jobPilot.telegram().pollingEnabled()) {
            fail("One-shot command requires Telegram polling to be disabled");
        }
        if (startupPreview.enabled()) {
            fail("Startup preview flag must remain disabled in one-shot command mode");
        }
    }

    private void fail(String safeMessage) {
        throw new CommandFailure(safeMessage);
    }

    private static final class CommandFailure extends RuntimeException {
        private CommandFailure(String safeMessage) {
            super(safeMessage);
        }
    }
}
