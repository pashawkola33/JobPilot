package com.jobpilot.telegram.commands;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.applications.application.ApplicationView;
import com.jobpilot.applications.application.ApplicationMutationResult;
import com.jobpilot.applications.domain.ApplicationStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramMessageRendererTest {
    private final TelegramMessageRenderer renderer = new TelegramMessageRenderer();

    @Test
    void escapesEveryDynamicHtmlValueAndStaysWithinTelegramLimit() {
        ApplicationView application = new ApplicationView(1L, 2L, "Java <Intern>",
                "A & B", "https://example.test/jobs/2?a=1&b=2", ApplicationStatus.REJECTED,
                Instant.parse("2026-07-19T10:00:00Z"), null, null,
                "<b>not markup</b>", "role & budget", Instant.parse("2026-07-19T10:00:00Z"));

        String rendered = renderer.application(application);

        assertThat(rendered).contains("Java &lt;Intern&gt;", "A &amp; B",
                "&lt;b&gt;not markup&lt;/b&gt;", "role &amp; budget", "a=1&amp;b=2");
        assertThat(rendered.length()).isLessThanOrEqualTo(4096);
    }

    @Test
    void listsAtMostTwentyAndBoundsMessage() {
        ApplicationView value = new ApplicationView(1L, 2L, "T".repeat(500),
                "C".repeat(300), "https://example.test/" + "p".repeat(1500),
                ApplicationStatus.SAVED, null, null, null, null, null,
                Instant.parse("2026-07-19T10:00:00Z"));
        String rendered = renderer.applications(java.util.Collections.nCopies(25, value));
        assertThat(rendered.length()).isLessThanOrEqualTo(4096);
        assertThat(rendered.split("Status", -1).length).isLessThanOrEqualTo(21);
    }

    @Test
    void oversizedDynamicValuesNeverSplitHtmlTagsOrEntities() {
        String hostile = "<&\"'".repeat(3_000);
        ApplicationView value = new ApplicationView(1L, 987L, hostile, hostile,
                "https://example.test/?q=" + hostile, ApplicationStatus.REJECTED,
                Instant.parse("2026-07-19T10:00:00Z"), null, null, hostile, hostile,
                Instant.parse("2026-07-19T10:00:00Z"));

        String application = renderer.application(value);
        String mutation = renderer.mutation(new ApplicationMutationResult(value, true));
        String list = renderer.applications(List.of(value, value));

        assertThat(application).contains("Job ID: 987", "Status: <b>REJECTED</b>");
        assertThat(mutation).contains("Application updated", "Status: <b>REJECTED</b>");
        assertThat(list).startsWith("<b>Applications</b>");
        assertSafeTelegramHtml(application);
        assertSafeTelegramHtml(mutation);
        assertSafeTelegramHtml(list);
    }

    private void assertSafeTelegramHtml(String html) {
        assertThat(html.length()).isLessThanOrEqualTo(4096);
        assertThat(count(html, "<b>")).isEqualTo(count(html, "</b>"));
        assertThat(count(html, "<a href=\"")).isEqualTo(count(html, "</a>"));
        String textOnly = html.replaceAll("</?b>", "")
                .replaceAll("<a href=\"[^\"]*\">", "")
                .replace("</a>", "");
        assertThat(textOnly).doesNotContain("<", ">");
        assertThat(textOnly.replaceAll("&(?:amp|lt|gt|quot|#39|hellip);", "")).doesNotContain("&");
    }

    private int count(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
