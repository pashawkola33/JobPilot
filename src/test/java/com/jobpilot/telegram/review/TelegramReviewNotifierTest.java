package com.jobpilot.telegram.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobreview.application.JobQueueItem;
import com.jobpilot.jobreview.application.JobReviewService;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.api.TelegramClient;
import com.jobpilot.telegram.api.TelegramTransportException;
import com.jobpilot.telegram.commands.TelegramMessageRenderer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelegramReviewNotifierTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-04T09:00:00Z"),
            ZoneOffset.UTC);
    private static final String TOKEN = "1234:obviously-fake";

    private TelegramClient client;
    private TelegramJobDeliveryRepository deliveries;
    private JobReviewService review;
    private final List<TelegramJobDelivery> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        deliveries = mock(TelegramJobDeliveryRepository.class);
        review = mock(JobReviewService.class);
        saved.clear();
        when(deliveries.findDeliveredJobIds(anyLong(), any(), any())).thenReturn(List.of());
        when(deliveries.existsByChatIdAndJobIdAndDeliveryType(anyLong(), anyLong(), any()))
                .thenReturn(false);
        when(deliveries.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
    }

    private TelegramReviewNotifier notifier(JobPilotProperties.Telegram settings) {
        return new TelegramReviewNotifier(client, deliveries, review,
                new TelegramMessageRenderer(), TestProperties.create(settings), CLOCK);
    }

    private JobPilotProperties.Telegram enabled(List<String> chats, int cap) {
        return new JobPilotProperties.Telegram(TOKEN, true, chats, true, true, cap, 500);
    }

    private JobQueueItem item(long id) {
        return new JobQueueItem(id, "Java Intern " + id, "Acme", "Bucharest",
                ScreeningDisposition.MATCH, 90, WorkflowStatus.UNREVIEWED, "greenhouse",
                "acme", Instant.parse("2026-08-01T10:00:00Z"), "https://example.test/jobs/" + id);
    }

    private void stub(ScreeningDisposition disposition, List<JobQueueItem> items) {
        when(review.notifiable(eq(disposition), any())).thenReturn(items);
    }

    @Test
    void makesZeroTelegramCallsWhenTheBotIsDisabled() {
        stub(ScreeningDisposition.MATCH, List.of(item(1)));
        stub(ScreeningDisposition.REVIEW, List.of(item(2)));

        notifier(new JobPilotProperties.Telegram("", "")).notifyIngestion(List.of(1L), List.of(2L));

        verifyNoInteractions(client);
        verifyNoInteractions(review);
        assertThat(saved).isEmpty();
    }

    @Test
    void makesZeroTelegramCallsWhenNoChatIsAllowed() {
        notifier(new JobPilotProperties.Telegram(TOKEN, false, List.of(), true, true, 5, 500))
                .notifyIngestion(List.of(1L), List.of(2L));

        verifyNoInteractions(client);
    }

    @Test
    void sendsOneCardPerNewMatchAndRecordsDeliveryOnlyAfterSuccess() {
        stub(ScreeningDisposition.MATCH, List.of(item(1), item(2)));
        stub(ScreeningDisposition.REVIEW, List.of());

        notifier(enabled(List.of("777"), 5)).notifyIngestion(List.of(1L, 2L), List.of());

        verify(client, org.mockito.Mockito.times(2)).sendMessage(eq("777"), anyString(), any());
        assertThat(saved).hasSize(2)
                .allSatisfy(row -> assertThat(row.getDeliveryType())
                        .isEqualTo(DeliveryType.MATCH_NOTIFICATION))
                .extracting(TelegramJobDelivery::getJobId).containsExactly(1L, 2L);
        assertThat(saved.getFirst().getDeliveredAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void neverResendsAMatchThatWasAlreadyDelivered() {
        stub(ScreeningDisposition.MATCH, List.of(item(1), item(2)));
        stub(ScreeningDisposition.REVIEW, List.of());
        when(deliveries.findDeliveredJobIds(eq(777L), eq(DeliveryType.MATCH_NOTIFICATION), any()))
                .thenReturn(List.of(1L));

        notifier(enabled(List.of("777"), 5)).notifyIngestion(List.of(1L, 2L), List.of());

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(client).sendMessage(eq("777"), html.capture(), any());
        assertThat(html.getValue()).contains("Java Intern 2").doesNotContain("Java Intern 1");
        assertThat(saved).singleElement().satisfies(row ->
                assertThat(row.getJobId()).isEqualTo(2L));
    }

    @Test
    void deliversIndependentlyToEveryAllowedChat() {
        stub(ScreeningDisposition.MATCH, List.of(item(1)));
        stub(ScreeningDisposition.REVIEW, List.of());

        notifier(enabled(List.of("777", "888"), 5)).notifyIngestion(List.of(1L), List.of());

        verify(client).sendMessage(eq("777"), anyString(), any());
        verify(client).sendMessage(eq("888"), anyString(), any());
        assertThat(saved).extracting(TelegramJobDelivery::getChatId)
                .containsExactlyInAnyOrder(777L, 888L);
    }

    @Test
    void leavesAFailedSendRetryableByWritingNoDeliveryRow() {
        stub(ScreeningDisposition.MATCH, List.of(item(1)));
        stub(ScreeningDisposition.REVIEW, List.of());
        doThrow(new TelegramTransportException(TelegramTransportException.Operation.SEND_MESSAGE))
                .when(client).sendMessage(anyString(), anyString(), any());

        notifier(enabled(List.of("777"), 5)).notifyIngestion(List.of(1L), List.of());

        assertThat(saved).isEmpty();
    }

    @Test
    void retriesTheSameMatchOnTheNextRunAfterAFailure() {
        stub(ScreeningDisposition.MATCH, List.of(item(1)));
        stub(ScreeningDisposition.REVIEW, List.of());
        TelegramReviewNotifier notifier = notifier(enabled(List.of("777"), 5));
        doThrow(new TelegramTransportException(TelegramTransportException.Operation.SEND_MESSAGE))
                .when(client).sendMessage(anyString(), anyString(), any());
        notifier.notifyIngestion(List.of(1L), List.of());
        assertThat(saved).isEmpty();

        org.mockito.Mockito.reset(client);
        notifier.notifyIngestion(List.of(1L), List.of());

        verify(client).sendMessage(eq("777"), anyString(), any());
        assertThat(saved).singleElement().satisfies(row ->
                assertThat(row.getJobId()).isEqualTo(1L));
    }

    @Test
    void capsAutomaticCardsAndSummarisesTheRemainder() {
        stub(ScreeningDisposition.MATCH, List.of(item(1), item(2), item(3), item(4), item(5)));
        stub(ScreeningDisposition.REVIEW, List.of());

        notifier(enabled(List.of("777"), 2)).notifyIngestion(
                List.of(1L, 2L, 3L, 4L, 5L), List.of());

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.times(3)).sendMessage(eq("777"), html.capture(), any());
        assertThat(html.getAllValues().getLast()).contains("3 further new MATCH")
                .contains("/matches");
        // Only the two vacancies actually sent as cards are recorded as delivered.
        assertThat(saved).hasSize(2).extracting(TelegramJobDelivery::getJobId)
                .containsExactly(1L, 2L);
    }

    @Test
    void sendsOneCompactReviewDigestRatherThanOneMessagePerVacancy() {
        stub(ScreeningDisposition.MATCH, List.of());
        stub(ScreeningDisposition.REVIEW,
                List.of(item(1), item(2), item(3), item(4), item(5), item(6)));

        notifier(enabled(List.of("777"), 3)).notifyIngestion(List.of(),
                List.of(1L, 2L, 3L, 4L, 5L, 6L));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(client).sendMessage(eq("777"), html.capture(), any());
        assertThat(html.getValue()).contains("6 new REVIEW").contains("/review")
                .contains("Java Intern 1").doesNotContain("Java Intern 4");
        // Every digested vacancy is deduplicated, including the ones beyond the preview.
        assertThat(saved).hasSize(6).allSatisfy(row -> assertThat(row.getDeliveryType())
                .isEqualTo(DeliveryType.REVIEW_DIGEST_ITEM));
    }

    @Test
    void neverRepeatsADigestItem() {
        stub(ScreeningDisposition.MATCH, List.of());
        stub(ScreeningDisposition.REVIEW, List.of(item(1), item(2)));
        when(deliveries.findDeliveredJobIds(eq(777L), eq(DeliveryType.REVIEW_DIGEST_ITEM), any()))
                .thenReturn(List.of(1L, 2L));

        notifier(enabled(List.of("777"), 5)).notifyIngestion(List.of(), List.of(1L, 2L));

        verify(client, never()).sendMessage(anyString(), anyString(), any());
        assertThat(saved).isEmpty();
    }

    @Test
    void honoursTheMatchAndDigestSwitchesIndependently() {
        stub(ScreeningDisposition.MATCH, List.of(item(1)));
        stub(ScreeningDisposition.REVIEW, List.of(item(2)));

        notifier(new JobPilotProperties.Telegram(TOKEN, true, List.of("777"), false, true, 5, 500))
                .notifyIngestion(List.of(1L), List.of(2L));

        verify(review, never()).notifiable(eq(ScreeningDisposition.MATCH), any());
        verify(review).notifiable(eq(ScreeningDisposition.REVIEW), any());
    }

    @Test
    void asksOnlyForMatchAndReviewSoRejectedVacanciesAreNeverConsidered() {
        stub(ScreeningDisposition.MATCH, List.of());
        stub(ScreeningDisposition.REVIEW, List.of());

        notifier(enabled(List.of("777"), 5)).notifyIngestion(List.of(1L), List.of(2L));

        ArgumentCaptor<ScreeningDisposition> dispositions =
                ArgumentCaptor.forClass(ScreeningDisposition.class);
        verify(review, org.mockito.Mockito.times(2)).notifiable(dispositions.capture(), any());
        assertThat(dispositions.getAllValues())
                .containsExactly(ScreeningDisposition.MATCH, ScreeningDisposition.REVIEW)
                .doesNotContain(ScreeningDisposition.REJECT);
    }

    @Test
    void pushesOnlyTheIdentifiersFromTheRunThatJustFinished() {
        stub(ScreeningDisposition.MATCH, List.of());
        stub(ScreeningDisposition.REVIEW, List.of());

        notifier(enabled(List.of("777"), 5)).notifyIngestion(List.of(), List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(review, org.mockito.Mockito.times(2)).notifiable(any(), ids.capture());
        assertThat(ids.getAllValues()).allSatisfy(value -> assertThat(value).isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void swallowsAnyNotificationFailureSoIngestionCannotFail() {
        when(review.notifiable(any(), any())).thenThrow(new IllegalStateException("database down"));

        notifier(enabled(List.of("777"), 5)).notifyIngestion(List.of(1L), List.of(2L));

        assertThat(saved).isEmpty();
    }

    @Test
    void attachesInlineActionsToEveryMatchCard() {
        stub(ScreeningDisposition.MATCH, List.of(item(1)));
        stub(ScreeningDisposition.REVIEW, List.of());

        notifier(enabled(List.of("777"), 5)).notifyIngestion(List.of(1L), List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<List<Map<String, String>>>> buttons =
                ArgumentCaptor.forClass(List.class);
        verify(client).sendMessage(eq("777"), anyString(), buttons.capture());
        List<Map<String, String>> flat = buttons.getValue().stream().flatMap(List::stream).toList();
        assertThat(flat).extracting(button -> button.get("text"))
                .containsExactly("Open vacancy", "Save", "Applied", "Dismiss", "Reset");
    }
}
