package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.telegram.polling.TelegramUpdatePoller;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Telegram long polling must survive a scheduler thread that ingestion has parked for
 * minutes. These tests use latches and in-memory tasks only; nothing reaches the network.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jobpilot-scheduler-isolation;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class TelegramSchedulerIsolationTest {

    @Autowired
    @Qualifier("taskScheduler")
    private ThreadPoolTaskScheduler applicationScheduler;

    @Autowired
    @Qualifier(SchedulingConfiguration.TELEGRAM_SCHEDULER)
    private ThreadPoolTaskScheduler telegramScheduler;

    @Test
    void aDedicatedSingleThreadTelegramSchedulerExists() {
        assertThat(telegramScheduler).isNotNull().isNotSameAs(applicationScheduler);
        assertThat(telegramScheduler.getPoolSize()).isEqualTo(1);
        assertThat(telegramScheduler.getThreadNamePrefix())
                .isEqualTo(SchedulingConfiguration.TELEGRAM_THREAD_PREFIX);
    }

    @Test
    void theApplicationSchedulerRemainsSeparateAndUnwidened() {
        // Ingestion concurrency must not change: the shared pool stays at its configured size.
        assertThat(applicationScheduler.getPoolSize()).isEqualTo(1);
        assertThat(applicationScheduler.getThreadNamePrefix())
                .isNotEqualTo(SchedulingConfiguration.TELEGRAM_THREAD_PREFIX);
    }

    @Test
    void theTelegramPollIsBoundToTheDedicatedSchedulerAndNothingElseIs() throws Exception {
        Method poll = TelegramUpdatePoller.class.getMethod("scheduledPoll");

        assertThat(poll.getAnnotation(Scheduled.class).scheduler())
                .isEqualTo(SchedulingConfiguration.TELEGRAM_SCHEDULER);

        // Ingestion and the digest stay on the application scheduler.
        for (String method : new String[] {"fetchJobs", "dailyDigest"}) {
            Scheduled scheduled = com.jobpilot.scheduling.JobSchedulingService.class
                    .getMethod(method).getAnnotation(Scheduled.class);
            assertThat(scheduled).as(method).isNotNull();
            assertThat(scheduled.scheduler()).as(method).isEmpty();
        }
    }

    @Test
    void blockingTheApplicationSchedulerDoesNotStopTelegramPolling() throws Exception {
        CountDownLatch ingestionStarted = new CountDownLatch(1);
        CountDownLatch releaseIngestion = new CountDownLatch(1);
        CountDownLatch pollsObserved = new CountDownLatch(3);
        AtomicReference<String> pollThread = new AtomicReference<>();

        // Stand in for a full ingestion: occupies the application scheduler thread.
        applicationScheduler.schedule(() -> {
            ingestionStarted.countDown();
            try {
                releaseIngestion.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, java.time.Instant.now());

        assertThat(ingestionStarted.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            telegramScheduler.scheduleWithFixedDelay(() -> {
                pollThread.set(Thread.currentThread().getName());
                pollsObserved.countDown();
            }, Duration.ofMillis(50));

            // The whole point: poll cycles keep running while the other scheduler is parked.
            assertThat(pollsObserved.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pollThread.get()).startsWith(SchedulingConfiguration.TELEGRAM_THREAD_PREFIX);
            assertThat(ingestionStarted.getCount()).isZero();
            assertThat(releaseIngestion.getCount()).isEqualTo(1); // still blocked
        } finally {
            releaseIngestion.countDown();
        }
    }

    @Test
    void theTelegramSchedulerNeverRunsTwoCyclesAtOnce() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch cycles = new CountDownLatch(4);

        telegramScheduler.scheduleWithFixedDelay(() -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
                cycles.countDown();
            }
        }, Duration.ofMillis(1));

        assertThat(cycles.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    void bothSchedulersShutDownGracefully() {
        ThreadPoolTaskScheduler telegram = new SchedulingConfiguration().telegramTaskScheduler(
                new org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder());
        ThreadPoolTaskScheduler application = new SchedulingConfiguration().taskScheduler(
                new org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder());
        telegram.initialize();
        application.initialize();

        telegram.shutdown();
        application.shutdown();

        assertThat(telegram.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(application.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
    }

    @Test
    void theTelegramSchedulerHasABoundedErrorHandlerSoAThrowDoesNotCancelPolling()
            throws Exception {
        CountDownLatch cycles = new CountDownLatch(3);

        telegramScheduler.scheduleWithFixedDelay(() -> {
            cycles.countDown();
            throw new IllegalStateException("boom");
        }, Duration.ofMillis(20));

        // Without an error handler the repeating task would be cancelled after the first throw.
        assertThat(cycles.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
