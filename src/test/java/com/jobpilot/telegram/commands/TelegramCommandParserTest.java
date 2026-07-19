package com.jobpilot.telegram.commands;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.support.TestProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import com.jobpilot.resume.domain.DocumentFormat;
import org.junit.jupiter.api.Test;

class TelegramCommandParserTest {
    private final TelegramCommandParser parser = parser("@JobPilotBot");

    @Test
    void parsesEverySupportedCommandAndBotNameSuffix() {
        Map<String, TelegramCommand.Kind> commands = Map.ofEntries(
                Map.entry("/help@JobPilotBot", TelegramCommand.Kind.HELP),
                Map.entry("/add https://jobs.example/1", TelegramCommand.Kind.ADD),
                Map.entry("/save 12", TelegramCommand.Kind.SAVE),
                Map.entry("/applied 12", TelegramCommand.Kind.APPLIED),
                Map.entry("/interview 12 2026-08-01T14:30:00+03:00", TelegramCommand.Kind.INTERVIEW),
                Map.entry("/rejected 12 role closed", TelegramCommand.Kind.REJECTED),
                Map.entry("/offer 12", TelegramCommand.Kind.OFFER),
                Map.entry("/withdraw 12", TelegramCommand.Kind.WITHDRAW),
                Map.entry("/followup 12 2026-08-15", TelegramCommand.Kind.FOLLOWUP),
                Map.entry("/note 12 follow up Friday", TelegramCommand.Kind.NOTE),
                Map.entry("/status 12", TelegramCommand.Kind.STATUS),
                Map.entry("/history 12", TelegramCommand.Kind.HISTORY),
                Map.entry("/analyze 12", TelegramCommand.Kind.ANALYZE),
                Map.entry("/documents 12 all both", TelegramCommand.Kind.DOCUMENTS),
                Map.entry("/resumes 12", TelegramCommand.Kind.RESUMES),
                Map.entry("/covernotes 12", TelegramCommand.Kind.COVER_NOTES),
                Map.entry("/selectdocs 12 91 none", TelegramCommand.Kind.SELECT_DOCUMENTS),
                Map.entry("/applications INTERVIEW", TelegramCommand.Kind.APPLICATIONS));

        commands.forEach((text, kind) -> assertThat(parser.parse(text).command().kind()).isEqualTo(kind));
        assertThat(parser.parse("/interview 12 2026-08-01T14:30:00+03:00").command().instant())
                .isEqualTo(Instant.parse("2026-08-01T11:30:00Z"));
        assertThat(parser.parse("/followup 12 2026-08-15").command().date())
                .isEqualTo(LocalDate.parse("2026-08-15"));
        assertThat(parser.parse("/applications interview").command().statusFilter())
                .isEqualTo(ApplicationStatus.INTERVIEW);
        TelegramCommand documents = parser.parse("/documents 12 resume pdf").command();
        assertThat(documents.documentScope()).isEqualTo(TelegramCommand.DocumentScope.RESUME);
        assertThat(documents.documentFormats()).isEqualTo(Set.of(DocumentFormat.PDF));
        TelegramCommand selection = parser.parse("/selectdocs 12 91 92").command();
        assertThat(selection.resumeVersionId()).isEqualTo(91L);
        assertThat(selection.coverNoteId()).isEqualTo(92L);
    }

    @Test
    void clearCommandsProduceNullValues() {
        assertThat(parser.parse("/followup 4 clear").command().date()).isNull();
        assertThat(parser.parse("/note 4 clear").command().text()).isNull();
    }

    @Test
    void rejectsMalformedIdsDatesDatetimesAndBounds() {
        assertThat(parser.parse("/save 1;shutdown").valid()).isFalse();
        assertThat(parser.parse("/save -1").valid()).isFalse();
        assertThat(parser.parse("/interview 1 2026-08-01T10:00:00").valid()).isFalse();
        assertThat(parser.parse("/followup 1 08/01/2026").valid()).isFalse();
        assertThat(parser.parse("/applications UNKNOWN").valid()).isFalse();
        assertThat(parser.parse("/note 1 " + "x".repeat(2001)).valid()).isFalse();
        assertThat(parser.parse("/add " + "x".repeat(4096)).valid()).isFalse();
        assertThat(parser.parse("/documents 1 cover both").valid()).isFalse();
        assertThat(parser.parse("/documents 1 all exe").valid()).isFalse();
        assertThat(parser.parse("/selectdocs 1 -2 none").valid()).isFalse();
        assertThat(parser.parse("/selectdocs 1 2 3 extra").valid()).isFalse();
    }

    @Test
    void unknownCommandReturnsHelp() {
        assertThat(parser.parse("/does-not-exist").command().kind())
                .isEqualTo(TelegramCommand.Kind.HELP);
    }

    @Test
    void acceptsSuffixlessAndMatchingAddressButNeverExecutesForeignAddress() {
        assertThat(parser.parse("/save 5").command().kind()).isEqualTo(TelegramCommand.Kind.SAVE);
        assertThat(parser.parse("/save@jObPiLoTbOt 5").command().kind())
                .isEqualTo(TelegramCommand.Kind.SAVE);
        assertThat(parser.parse("/save@OtherBot 5").command().kind())
                .isEqualTo(TelegramCommand.Kind.HELP);
    }

    private TelegramCommandParser parser(String username) {
        var telegram = new JobPilotProperties.Telegram(
                "fake-token", "-100555", username, true, "-100555", "777",
                Duration.ofSeconds(25), Duration.ofSeconds(2), 50, 3, true);
        return new TelegramCommandParser(TestProperties.create(telegram));
    }
}
