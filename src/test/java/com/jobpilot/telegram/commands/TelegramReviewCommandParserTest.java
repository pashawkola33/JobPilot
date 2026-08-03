package com.jobpilot.telegram.commands;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobreview.application.JobQueue;
import com.jobpilot.support.TestProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramReviewCommandParserTest {
    private final TelegramCommandParser parser = new TelegramCommandParser(
            TestProperties.create(new JobPilotProperties.Telegram(
                    "1234:obviously-fake", true, List.of("777"), true, true, 5, 20)));

    @Test
    void parsesEveryReviewCommand() {
        assertThat(parser.parse("/start").command().kind()).isEqualTo(TelegramCommand.Kind.START);
        assertThat(parser.parse("/help").command().kind()).isEqualTo(TelegramCommand.Kind.HELP);
        assertThat(parser.parse("/stats").command().kind()).isEqualTo(TelegramCommand.Kind.STATS);
        assertThat(parser.parse("/job 12").command())
                .satisfies(command -> {
                    assertThat(command.kind()).isEqualTo(TelegramCommand.Kind.JOB);
                    assertThat(command.jobId()).isEqualTo(12L);
                });
        assertThat(parser.parse("/reset 12").command().kind()).isEqualTo(TelegramCommand.Kind.RESET);
    }

    @Test
    void mapsEachQueueCommandToItsQueue() {
        assertThat(parser.parse("/matches").command().queue()).isEqualTo(JobQueue.MATCHES);
        assertThat(parser.parse("/review").command().queue()).isEqualTo(JobQueue.REVIEW);
        assertThat(parser.parse("/saved").command().queue()).isEqualTo(JobQueue.SAVED);
        assertThat(parser.parse("/applied").command().queue()).isEqualTo(JobQueue.APPLIED);
    }

    @Test
    void treatsPagesAsOneBasedAndBoundsThem() {
        assertThat(parser.parse("/matches").command().page()).isZero();
        assertThat(parser.parse("/matches 3").command().page()).isEqualTo(2);
        assertThat(parser.parse("/matches 999").command().page()).isEqualTo(998);
        assertThat(parser.parse("/matches 0").valid()).isFalse();
        assertThat(parser.parse("/matches 1000").valid()).isFalse();
        assertThat(parser.parse("/matches abc").valid()).isFalse();
    }

    @Test
    void keepsTheApplicationTrackingTransitionWhenAppliedCarriesAJobId() {
        TelegramCommand withId = parser.parse("/applied 42").command();

        assertThat(withId.kind()).isEqualTo(TelegramCommand.Kind.APPLIED);
        assertThat(withId.jobId()).isEqualTo(42L);
        assertThat(withId.queue()).isNull();
    }

    @Test
    void rejectsMalformedJobIdentifiers() {
        for (String command : List.of("/job", "/job 0", "/job -1", "/job abc", "/job 1.5",
                "/job 99999999999999999999", "/reset", "/reset x")) {
            assertThat(parser.parse(command).valid()).as(command).isFalse();
            assertThat(parser.parse(command).error()).as(command).isNotBlank();
        }
    }

    @Test
    void bindsNotesToTheConfiguredMaximumLength() {
        assertThat(parser.parse("/note 4 " + "n".repeat(20)).valid()).isTrue();
        TelegramCommandParseResult tooLong = parser.parse("/note 4 " + "n".repeat(21));
        assertThat(tooLong.valid()).isFalse();
        assertThat(tooLong.error()).contains("20 characters");
    }

    @Test
    void treatsClearAsANullNote() {
        assertThat(parser.parse("/note 4 clear").command().text()).isNull();
        assertThat(parser.parse("/note 4 keep this").command().text()).isEqualTo("keep this");
    }

    @Test
    void fallsBackToHelpForAnUnknownCommand() {
        assertThat(parser.parse("/nope").command().kind()).isEqualTo(TelegramCommand.Kind.HELP);
        assertThat(parser.parse("not a command").valid()).isFalse();
    }
}
