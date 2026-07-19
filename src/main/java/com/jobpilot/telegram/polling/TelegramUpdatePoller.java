package com.jobpilot.telegram.polling;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.telegram.api.TelegramClient;
import com.jobpilot.telegram.api.TelegramUpdate;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TelegramUpdatePoller {
    static final int MAX_FIRST_START_DISCARD_PAGES = 100;
    private static final Logger log = LoggerFactory.getLogger(TelegramUpdatePoller.class);
    private final TelegramClient client;
    private final TelegramUpdateProcessor processor;
    private final TelegramBotStateService state;
    private final JobPilotProperties.Telegram settings;
    private final AtomicBoolean polling = new AtomicBoolean();

    public TelegramUpdatePoller(TelegramClient client, TelegramUpdateProcessor processor,
                                TelegramBotStateService state, JobPilotProperties properties) {
        this.client = client;
        this.processor = processor;
        this.state = state;
        this.settings = properties.telegram();
    }

    @Scheduled(fixedDelayString = "#{@telegramPollDelay}")
    public void scheduledPoll() {
        if (settings.commandsEnabled()) pollOnce();
    }

    public boolean pollOnce() {
        if (!settings.commandsEnabled() || !polling.compareAndSet(false, true)) return false;
        try {
            var initial = state.load();
            if (initial.isEmpty() && settings.discardPendingOnFirstStart()) {
                discardPendingUpdates();
                return true;
            }
            Long lastProcessed = initial.map(
                    TelegramBotStateService.StateSnapshot::lastProcessedUpdateId).orElse(null);
            Long offset = lastProcessed == null ? null : lastProcessed + 1;
            List<TelegramUpdate> updates = client.getUpdates(
                    offset, settings.pollTimeout(), settings.pollLimit()).stream()
                    .sorted(Comparator.comparingLong(TelegramUpdate::updateId)).toList();
            process(updates, lastProcessed);
            return true;
        } catch (RuntimeException transportOrStateFailure) {
            log.warn("Telegram polling cycle failed category=poll_or_transport_failure");
            return true;
        } finally {
            polling.set(false);
        }
    }

    private void process(List<TelegramUpdate> updates, Long lastProcessed) {
        long watermark = lastProcessed == null ? Long.MIN_VALUE : lastProcessed;
        for (TelegramUpdate update : updates) {
            if (update.updateId() <= watermark) continue;
            try {
                processor.process(update);
                state.markProcessed(update.updateId());
                watermark = update.updateId();
            } catch (RuntimeException internalFailure) {
                int attempts = state.recordFailure(update.updateId());
                if (attempts >= settings.maxUpdateFailures()) {
                    state.markProcessed(update.updateId());
                    watermark = update.updateId();
                    log.error("Telegram update dead-lettered updateId={} category=internal_failure",
                            update.updateId());
                    continue;
                }
                log.warn("Telegram update will be retried updateId={} category=internal_failure attempt={}",
                        update.updateId(), attempts);
                break;
            }
        }
    }

    private void discardPendingUpdates() {
        Long offset = null;
        Long highest = null;
        for (int page = 0; page < MAX_FIRST_START_DISCARD_PAGES; page++) {
            List<TelegramUpdate> batch = client.getUpdates(offset, Duration.ZERO, settings.pollLimit())
                    .stream().sorted(Comparator.comparingLong(TelegramUpdate::updateId)).toList();
            if (batch.isEmpty()) {
                if (highest == null) state.initialize(null);
                log.info("Telegram first-start pending-update discard completed");
                return;
            }
            highest = batch.getLast().updateId();
            state.initialize(highest);
            state.markProcessed(highest);
            offset = highest + 1;
        }
        log.error("Telegram first-start pending-update discard stopped category=safety_page_limit");
    }
}
