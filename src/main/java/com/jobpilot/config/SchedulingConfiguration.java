package com.jobpilot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Registers every scheduled task only when the normal runtime explicitly permits it. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jobpilot.scheduled-tasks-enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfiguration {
    /** Bean name that {@code @Scheduled(scheduler = ...)} binds Telegram polling to. */
    public static final String TELEGRAM_SCHEDULER = "telegramTaskScheduler";
    public static final String TELEGRAM_THREAD_PREFIX = "telegram-scheduling-";

    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulingConfiguration.class);

    /**
     * The application scheduler, carrying ingestion and the daily digest.
     *
     * <p>Declared explicitly because defining any {@code TaskScheduler} bean makes Spring Boot
     * back off its auto-configured one. Without this bean the Telegram scheduler below would
     * become the only candidate and would end up running ingestion — exactly the coupling this
     * configuration removes. Built from Boot's own builder so every
     * {@code spring.task.scheduling.*} property, including the 20-second shutdown window,
     * still applies unchanged.
     */
    @Bean
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    /**
     * A scheduler used by nothing but Telegram long polling.
     *
     * <p>A Telegram {@code getUpdates} call parks its thread for the whole poll timeout, and a
     * full ingestion holds its thread for minutes. Sharing one thread meant whichever started
     * first starved the other: during a six-hourly run the bot stopped answering until the run
     * finished. One dedicated thread keeps the two independent without widening the shared
     * pool, so ingestion concurrency is unchanged.
     */
    @Bean(TELEGRAM_SCHEDULER)
    ThreadPoolTaskScheduler telegramTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        ThreadPoolTaskScheduler scheduler = builder
                // One thread only: two concurrent getUpdates calls would fight over the same
                // offset, and Telegram answers the second with a 409.
                .poolSize(1)
                .threadNamePrefix(TELEGRAM_THREAD_PREFIX)
                .build();
        // Last-resort guard. The poller already swallows its own failures; this keeps a
        // surprise from cancelling the repeating task, and logs the type only because a
        // transport message can carry the bot token inside a URL.
        scheduler.setErrorHandler(throwable -> LOGGER.warn(
                "Telegram scheduled poll failed outside the poller category=uncaught type={}",
                throwable.getClass().getName()));
        return scheduler;
    }
}
