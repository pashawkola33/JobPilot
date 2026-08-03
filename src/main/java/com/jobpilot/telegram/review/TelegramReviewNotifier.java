package com.jobpilot.telegram.review;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobreview.application.JobQueueItem;
import com.jobpilot.jobreview.application.JobReviewService;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.telegram.api.TelegramClient;
import com.jobpilot.telegram.commands.TelegramMessageRenderer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Post-ingestion push into the private review chats.
 *
 * <p>Only vacancies persisted by the run that just finished are considered, so enabling the
 * bot never replays history. A delivery row is written only after the Bot API confirms the
 * send, which makes a failed send retryable and a successful one final.
 */
@Component
public class TelegramReviewNotifier {
    private static final Logger log = LoggerFactory.getLogger(TelegramReviewNotifier.class);

    private final TelegramClient client;
    private final TelegramJobDeliveryRepository deliveries;
    private final JobReviewService review;
    private final TelegramMessageRenderer renderer;
    private final JobPilotProperties.Telegram settings;
    private final Clock clock;

    public TelegramReviewNotifier(TelegramClient client,
                                  TelegramJobDeliveryRepository deliveries,
                                  JobReviewService review,
                                  TelegramMessageRenderer renderer,
                                  JobPilotProperties properties,
                                  Clock clock) {
        this.client = client;
        this.deliveries = deliveries;
        this.review = review;
        this.renderer = renderer;
        this.settings = properties.telegram();
        this.clock = clock;
    }

    /** Never throws: a Telegram outage must not fail or roll back an ingestion run. */
    public void notifyIngestion(Collection<Long> newMatchJobIds, Collection<Long> newReviewJobIds) {
        if (!settings.enabled() || settings.allowedChatIds().isEmpty()) return;
        try {
            if (settings.matchNotificationsEnabled()) {
                deliverMatches(review.notifiable(ScreeningDisposition.MATCH, ids(newMatchJobIds)));
            }
            if (settings.reviewDigestEnabled()) {
                deliverDigest(review.notifiable(ScreeningDisposition.REVIEW, ids(newReviewJobIds)));
            }
        } catch (RuntimeException notificationFailure) {
            log.warn("Telegram review notification failed category={}",
                    notificationFailure.getClass().getSimpleName());
        }
    }

    private void deliverMatches(List<JobQueueItem> candidates) {
        if (candidates.isEmpty()) return;
        for (String chat : settings.allowedChatIds()) {
            long chatId = Long.parseLong(chat);
            List<JobQueueItem> pending = undelivered(chatId, DeliveryType.MATCH_NOTIFICATION,
                    candidates);
            if (pending.isEmpty()) continue;
            int cap = settings.maxJobsPerMessage();
            for (JobQueueItem item : pending.stream().limit(cap).toList()) {
                if (send(chat, renderer.matchCard(item),
                        renderer.jobButtons(item.id(), item.canonicalUrl()))) {
                    record(chatId, item.id(), DeliveryType.MATCH_NOTIFICATION);
                }
            }
            // Anything past the cap is summarised once and stays in /matches.
            if (pending.size() > cap) {
                send(chat, renderer.matchSummary(pending.size() - (long) cap), List.of());
            }
        }
    }

    private void deliverDigest(List<JobQueueItem> candidates) {
        if (candidates.isEmpty()) return;
        for (String chat : settings.allowedChatIds()) {
            long chatId = Long.parseLong(chat);
            List<JobQueueItem> pending = undelivered(chatId, DeliveryType.REVIEW_DIGEST_ITEM,
                    candidates);
            if (pending.isEmpty()) continue;
            List<JobQueueItem> shown = pending.stream().limit(settings.maxJobsPerMessage()).toList();
            // One compact digest per chat, never one message per REVIEW vacancy.
            if (!send(chat, renderer.reviewDigest(pending.size(), shown), List.of())) continue;
            for (JobQueueItem item : pending) {
                record(chatId, item.id(), DeliveryType.REVIEW_DIGEST_ITEM);
            }
        }
    }

    private List<JobQueueItem> undelivered(long chatId, DeliveryType type,
                                           List<JobQueueItem> candidates) {
        Set<Long> already = new HashSet<>(deliveries.findDeliveredJobIds(chatId, type,
                candidates.stream().map(JobQueueItem::id).toList()));
        return candidates.stream().filter(item -> !already.contains(item.id())).toList();
    }

    private boolean send(String chatId, String html, List<List<java.util.Map<String, String>>> buttons) {
        try {
            client.sendMessage(chatId, html, buttons);
            return true;
        } catch (RuntimeException failure) {
            // No delivery row is written, so the next run retries this vacancy.
            log.warn("Telegram review send failed category={}", failure.getClass().getSimpleName());
            return false;
        }
    }

    private void record(long chatId, long jobId, DeliveryType type) {
        if (deliveries.existsByChatIdAndJobIdAndDeliveryType(chatId, jobId, type)) return;
        deliveries.save(new TelegramJobDelivery(chatId, jobId, type, clock.instant()));
    }

    private List<Long> ids(Collection<Long> values) {
        return values == null ? List.of() : new ArrayList<>(new java.util.LinkedHashSet<>(values));
    }
}
