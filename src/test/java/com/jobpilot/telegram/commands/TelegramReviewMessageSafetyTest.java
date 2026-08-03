package com.jobpilot.telegram.commands;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobreview.application.JobDetailView;
import com.jobpilot.jobreview.application.JobQueue;
import com.jobpilot.jobreview.application.JobQueueItem;
import com.jobpilot.jobreview.application.JobQueuePage;
import com.jobpilot.jobreview.application.JobReasonView;
import com.jobpilot.jobreview.application.JobReviewStats;
import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TelegramReviewMessageSafetyTest {
    private static final int LIMIT = 4096;
    private static final String HOSTILE =
            "<script>alert('x')</script> & \"quoted\" 'single' <b>bold</b> [md](https://evil.test)";
    private static final Instant WHEN = Instant.parse("2026-08-01T10:00:00Z");

    private final TelegramMessageRenderer renderer = new TelegramMessageRenderer();

    private JobQueueItem item(long id, String url) {
        return new JobQueueItem(id, HOSTILE, HOSTILE, HOSTILE, ScreeningDisposition.MATCH, 91,
                WorkflowStatus.UNREVIEWED, HOSTILE, HOSTILE, WHEN, url);
    }

    private JobDetailView detail(String url) {
        return new JobDetailView(7, HOSTILE, HOSTILE, HOSTILE, url, 88,
                ScreeningDisposition.REVIEW, HOSTILE, HOSTILE,
                List.of(new JobReasonView("RELEVANCE", "R1", HOSTILE)), HOSTILE, HOSTILE,
                WHEN, WHEN, WorkflowStatus.SAVED, HOSTILE, null);
    }

    private void assertEscaped(String rendered) {
        assertThat(rendered).doesNotContain("<script>").doesNotContain("</script>");
        assertThat(rendered).contains("&lt;script&gt;").contains("&amp;").contains("&quot;");
        // Every raw angle bracket left in the message belongs to a tag the renderer emitted.
        // Element 0 is the text before the first tag, so only the rest are tag openings.
        String[] fragments = rendered.split("<", -1);
        for (int i = 1; i < fragments.length; i++) {
            assertThat(fragments[i]).as(fragments[i])
                    .matches("(?s)^(/?(b|i)>|a href=\"[^\"]*\">|/a>).*");
        }
    }

    @Test
    void escapesEveryDynamicFieldOfAJobCard() {
        assertEscaped(renderer.job(detail("https://example.test/jobs/7?a=1&b=2")));
    }

    @Test
    void escapesEveryDynamicFieldOfAQueueListing() {
        JobQueuePage page = new JobQueuePage(JobQueue.MATCHES,
                List.of(item(1, "https://example.test/1"), item(2, "https://example.test/2")),
                0, 5, 2);

        assertEscaped(renderer.queue(page));
    }

    @Test
    void escapesMatchCardsDigestsAndNotes() {
        assertEscaped(renderer.matchCard(item(3, "https://example.test/3")));
        assertEscaped(renderer.reviewDigest(9, List.of(item(4, "https://example.test/4"))));
        assertEscaped(renderer.workflow(new WorkflowView(5, WorkflowStatus.SAVED, HOSTILE,
                null, WHEN, true)));
    }

    @Test
    void staysWithinTheTelegramMessageLimitForHostileOversizedInput() {
        String huge = "Ω".repeat(4000);
        JobQueueItem big = new JobQueueItem(1, huge, huge, huge, ScreeningDisposition.MATCH, 90,
                WorkflowStatus.UNREVIEWED, huge, huge, WHEN, "https://example.test/1");
        JobDetailView bigDetail = new JobDetailView(1, huge, huge, huge, "https://example.test/1",
                90, ScreeningDisposition.MATCH, huge, huge,
                java.util.Collections.nCopies(50, new JobReasonView("RELEVANCE", "R", huge)),
                huge, huge, WHEN, WHEN, WorkflowStatus.SAVED, huge, null);

        assertThat(renderer.job(bigDetail).length()).isLessThanOrEqualTo(LIMIT);
        assertThat(renderer.matchCard(big).length()).isLessThanOrEqualTo(LIMIT);
        assertThat(renderer.queue(new JobQueuePage(JobQueue.REVIEW,
                java.util.Collections.nCopies(20, big), 0, 20, 20)).length())
                .isLessThanOrEqualTo(LIMIT);
        assertThat(renderer.reviewDigest(500,
                java.util.Collections.nCopies(20, big)).length()).isLessThanOrEqualTo(LIMIT);
    }

    @Test
    void truncatesWithoutSplittingASurrogatePair() {
        String emoji = "😀".repeat(3000);
        JobQueueItem item = new JobQueueItem(1, emoji, emoji, emoji, ScreeningDisposition.MATCH,
                90, WorkflowStatus.UNREVIEWED, emoji, emoji, WHEN, "https://example.test/1");

        String rendered = renderer.matchCard(item);

        assertThat(rendered.length()).isLessThanOrEqualTo(LIMIT);
        for (int i = 0; i < rendered.length(); i++) {
            char character = rendered.charAt(i);
            if (Character.isHighSurrogate(character)) {
                assertThat(i + 1).isLessThan(rendered.length());
                assertThat(Character.isLowSurrogate(rendered.charAt(i + 1))).isTrue();
            }
            assertThat(Character.isLowSurrogate(character) && (i == 0
                    || !Character.isHighSurrogate(rendered.charAt(i - 1)))).isFalse();
        }
    }

    @Test
    void acceptsOnlyCanonicalHttpsUrls() {
        assertThat(TelegramMessageRenderer.safeUrl("https://boards.example.test/jobs/1"))
                .isEqualTo("https://boards.example.test/jobs/1");
        assertThat(TelegramMessageRenderer.safeUrl("HTTPS://example.test/x"))
                .isEqualTo("HTTPS://example.test/x");
    }

    @Test
    void rejectsEveryUnsafeUrlScheme() {
        for (String unsafe : List.of("javascript:alert(1)", "http://example.test/x",
                "data:text/html;base64,PHNjcmlwdD4=", "file:///etc/passwd", "ftp://example.test",
                "//example.test/x", "https:/example.test", "https://", " ", "",
                "https://exa mple.test/x", "https://example.test/\"onclick=",
                "https://example.test/<script>", "https://example.test/" + "p".repeat(600))) {
            assertThat(TelegramMessageRenderer.safeUrl(unsafe)).as(unsafe).isNull();
        }
        assertThat(TelegramMessageRenderer.safeUrl(null)).isNull();
    }

    @Test
    void omitsTheOpenButtonAndLinkWhenTheStoredUrlIsUnsafe() {
        String rendered = renderer.job(detail("javascript:alert(1)"));

        assertThat(rendered).doesNotContain("javascript:").contains("No public link available");
        List<Map<String, String>> flattened = renderer.jobButtons(7, "javascript:alert(1)")
                .stream().flatMap(List::stream).toList();
        assertThat(flattened).noneMatch(button -> button.containsKey("url"));
        assertThat(renderer.jobButtons(7, "https://example.test/7").stream()
                .flatMap(List::stream).filter(button -> button.containsKey("url")).count())
                .isEqualTo(1);
    }

    @Test
    void putsNoTitleUrlNoteOrChatIdIntoCallbackData() {
        String callbackValues = renderer.jobButtons(7, "https://example.test/7").stream()
                .flatMap(List::stream)
                .map(button -> button.getOrDefault("callback_data", ""))
                .collect(Collectors.joining("|"));

        assertThat(callbackValues).doesNotContain("example.test").doesNotContain("script")
                .doesNotContain("777");
        assertThat(callbackValues.split("\\|")).allSatisfy(value ->
                assertThat(value).matches("^(jr:[sadr]:\\d+)?$"));
    }

    @Test
    void neverRendersAProviderDescriptionBecauseTheProjectionHasNone() {
        // JobDetailView carries no description component at all, so there is nothing to leak.
        assertThat(JobDetailView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("description");
    }

    @Test
    void statsAndStartCarryNoDynamicInput() {
        assertThat(renderer.stats(new JobReviewStats(1, 2, 3, 4, 5)))
                .contains("Unreviewed MATCH: 1", "Dismissed: 5");
        assertThat(renderer.start()).contains("/matches");
        assertThat(renderer.help()).contains("/review", "/stats", "/job", "/reset");
    }

    @Test
    void queueKeyboardOffersNextOnlyWhenAnotherPageExists() {
        JobQueuePage last = new JobQueuePage(JobQueue.MATCHES, List.of(item(1,
                "https://example.test/1")), 0, 5, 1);
        JobQueuePage more = new JobQueuePage(JobQueue.MATCHES, List.of(item(1,
                "https://example.test/1")), 0, 5, 12);

        assertThat(renderer.queueButtons(last)).isEmpty();
        assertThat(renderer.queueButtons(more).getFirst().getFirst())
                .containsEntry("callback_data", "jn:m:1");
    }
}
