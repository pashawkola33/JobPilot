package com.jobpilot.applications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.applications.domain.ApplicationStatusHistory;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.applications.repository.ApplicationStatusHistoryRepository;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.repository.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.RollbackException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:application-tracker;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import(ApplicationTrackerServiceTest.FixedClockConfig.class)
class ApplicationTrackerServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");
    @Autowired ApplicationTrackerService tracker;
    @Autowired ApplicationRepository applications;
    @Autowired ApplicationStatusHistoryRepository history;
    @Autowired JobRepository jobs;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired MutableClock testClock;

    @BeforeEach
    void resetClock() {
        testClock.set(NOW);
    }

    @Test
    void createsSavedAndAppliedWithOneHistoryRowAndApplicationDateOnlyForApplied() {
        Job savedJob = job("saved");
        Job appliedJob = job("applied");

        var saved = tracker.transition(savedJob.getId(), ApplicationStatus.SAVED,
                null, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        var applied = tracker.transition(appliedJob.getId(), ApplicationStatus.APPLIED,
                null, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND);

        assertThat(saved.application().applicationDate()).isNull();
        assertThat(applied.application().applicationDate()).isEqualTo(NOW);
        assertThat(history.countByApplicationId(saved.application().applicationId())).isEqualTo(1);
        assertThat(history.countByApplicationId(applied.application().applicationId())).isEqualTo(1);
    }

    @Test
    void supportsForwardTransitionsAndPreservesFirstApplicationDate() {
        Job job = job("forward");
        tracker.transition(job.getId(), ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        var applied = tracker.transition(job.getId(), ApplicationStatus.APPLIED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        Instant interview = Instant.parse("2026-07-25T12:00:00Z");
        var interviewed = tracker.transition(job.getId(), ApplicationStatus.INTERVIEW, interview, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        var offered = tracker.transition(job.getId(), ApplicationStatus.OFFER, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        var withdrawn = tracker.transition(job.getId(), ApplicationStatus.WITHDRAWN, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);

        assertThat(interviewed.application().applicationDate()).isEqualTo(applied.application().applicationDate());
        assertThat(interviewed.application().interviewDate()).isEqualTo(interview);
        assertThat(withdrawn.application().status()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(withdrawn.application().interviewDate()).isEqualTo(interview);
        assertThat(history.countByApplicationId(offered.application().applicationId())).isEqualTo(5);
    }

    @Test
    void supportsRejectionFromAppliedOrInterviewAndBoundsReason() {
        for (boolean interviewFirst : Set.of(false, true)) {
            Job job = job("reject-" + interviewFirst);
            tracker.transition(job.getId(), ApplicationStatus.APPLIED, null, null,
                    ApplicationStatusChangeSource.TELEGRAM_COMMAND);
            if (interviewFirst) tracker.transition(job.getId(), ApplicationStatus.INTERVIEW,
                    Instant.parse("2026-08-01T09:00:00Z"), null,
                    ApplicationStatusChangeSource.TELEGRAM_COMMAND);
            var rejected = tracker.transition(job.getId(), ApplicationStatus.REJECTED, null,
                    "  role   closed  ", ApplicationStatusChangeSource.TELEGRAM_COMMAND);
            assertThat(rejected.application().rejectionReason()).isEqualTo("role closed");
        }
        Job tooLong = job("reason-bound");
        tracker.transition(tooLong.getId(), ApplicationStatus.APPLIED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        assertThatThrownBy(() -> tracker.transition(tooLong.getId(), ApplicationStatus.REJECTED,
                null, "x".repeat(1001), ApplicationStatusChangeSource.TELEGRAM_COMMAND))
                .isInstanceOf(ApplicationTrackingException.class);
    }

    @Test
    void rejectsTerminalAndBackwardsTransitionsAndRequiresInterviewDate() {
        Job terminal = job("terminal");
        tracker.transition(terminal.getId(), ApplicationStatus.APPLIED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        tracker.transition(terminal.getId(), ApplicationStatus.REJECTED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        assertThatThrownBy(() -> tracker.transition(terminal.getId(), ApplicationStatus.OFFER,
                null, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND))
                .isInstanceOf(ApplicationTrackingException.class)
                .hasMessageContaining("not allowed");

        Job backwards = job("backwards");
        tracker.transition(backwards.getId(), ApplicationStatus.APPLIED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        assertThatThrownBy(() -> tracker.transition(backwards.getId(), ApplicationStatus.SAVED,
                null, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND))
                .isInstanceOf(ApplicationTrackingException.class);
        assertThatThrownBy(() -> tracker.transition(backwards.getId(), ApplicationStatus.INTERVIEW,
                null, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND))
                .isInstanceOf(ApplicationTrackingException.class)
                .hasMessageContaining("Interview date");
    }

    @Test
    void sameStatusIsIdempotentAndDoesNotAddHistory() {
        Job job = job("idempotent");
        var first = tracker.transition(job.getId(), ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        var duplicate = tracker.transition(job.getId(), ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_CALLBACK);

        assertThat(duplicate.changed()).isFalse();
        assertThat(history.countByApplicationId(first.application().applicationId())).isEqualTo(1);
    }

    @Test
    void reschedulesInterviewOnlyWhenDatetimeChangesAndRecordsExactHistory() {
        Job job = job("interview-reschedule");
        tracker.transition(job.getId(), ApplicationStatus.APPLIED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        Instant originalInterview = Instant.parse("2026-08-01T11:30:00Z");
        var original = tracker.transition(job.getId(), ApplicationStatus.INTERVIEW,
                originalInterview, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        Instant originalUpdatedAt = original.application().updatedAt();
        long applicationId = original.application().applicationId();

        Instant rescheduleTime = NOW.plusSeconds(3_600);
        Instant rescheduledInterview = Instant.parse("2026-08-02T12:45:00Z");
        testClock.set(rescheduleTime);
        var rescheduled = tracker.transition(job.getId(), ApplicationStatus.INTERVIEW,
                rescheduledInterview, null, ApplicationStatusChangeSource.TELEGRAM_CALLBACK);

        assertThat(rescheduled.changed()).isTrue();
        assertThat(rescheduled.application().interviewDate()).isEqualTo(rescheduledInterview);
        assertThat(rescheduled.application().updatedAt()).isEqualTo(rescheduleTime)
                .isAfter(originalUpdatedAt);
        List<ApplicationStatusHistory> entries =
                history.findByApplicationIdOrderByChangedAtAscIdAsc(applicationId);
        assertThat(entries).hasSize(3);
        ApplicationStatusHistory reschedule = entries.getLast();
        assertThat(reschedule.getPreviousStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(reschedule.getNewStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(reschedule.getChangedAt()).isEqualTo(rescheduleTime);
        assertThat(reschedule.getSource()).isEqualTo(ApplicationStatusChangeSource.TELEGRAM_CALLBACK);

        testClock.set(rescheduleTime.plusSeconds(3_600));
        var duplicate = tracker.transition(job.getId(), ApplicationStatus.INTERVIEW,
                rescheduledInterview, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        assertThat(duplicate.changed()).isFalse();
        assertThat(duplicate.application().updatedAt()).isEqualTo(rescheduleTime);
        assertThat(history.countByApplicationId(applicationId)).isEqualTo(3);

        assertThatThrownBy(() -> tracker.transition(job.getId(), ApplicationStatus.INTERVIEW,
                null, null, ApplicationStatusChangeSource.TELEGRAM_COMMAND))
                .isInstanceOf(ApplicationTrackingException.class)
                .hasMessageContaining("Interview date");
        assertThat(tracker.findByJobId(job.getId()).interviewDate()).isEqualTo(rescheduledInterview);
        assertThat(history.countByApplicationId(applicationId)).isEqualTo(3);
    }

    @Test
    void setsAndClearsFollowUpAndNormalizedNotes() {
        Job job = job("metadata");
        tracker.transition(job.getId(), ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        tracker.changeFollowUpDate(job.getId(), LocalDate.parse("2026-08-02"));
        tracker.changeNotes(job.getId(), "  contact   after holiday ");
        assertThat(tracker.findByJobId(job.getId()).nextFollowUpDate())
                .isEqualTo(LocalDate.parse("2026-08-02"));
        assertThat(tracker.findByJobId(job.getId()).notes()).isEqualTo("contact after holiday");

        tracker.changeFollowUpDate(job.getId(), null);
        tracker.changeNotes(job.getId(), null);
        assertThat(tracker.findByJobId(job.getId()).nextFollowUpDate()).isNull();
        assertThat(tracker.findByJobId(job.getId()).notes()).isNull();
    }

    @Test
    void concurrentCreateProducesOneApplicationAndOneHistoryRow() throws Exception {
        Job job = job("concurrent");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { start.await(); return save(job); });
            var two = executor.submit(() -> { start.await(); return save(job); });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).applicationId())
                    .isEqualTo(two.get(10, TimeUnit.SECONDS).applicationId());
        }
        ApplicationRecord stored = applications.findByJobId(job.getId()).orElseThrow();
        assertThat(history.countByApplicationId(stored.getId())).isEqualTo(1);
    }

    @Test
    void versionColumnRejectsAStaleUpdate() {
        Job job = job("optimistic");
        long applicationId = tracker.transition(job.getId(), ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.INTERNAL).application().applicationId();
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager stale = entityManagerFactory.createEntityManager();
        try {
            first.getTransaction().begin();
            stale.getTransaction().begin();
            ApplicationRecord current = first.find(ApplicationRecord.class, applicationId);
            ApplicationRecord old = stale.find(ApplicationRecord.class, applicationId);
            current.changeNotes("first", NOW.plusSeconds(1));
            first.getTransaction().commit();
            old.changeNotes("stale", NOW.plusSeconds(2));
            assertThatThrownBy(stale.getTransaction()::commit).isInstanceOf(RollbackException.class);
        } finally {
            if (first.getTransaction().isActive()) first.getTransaction().rollback();
            if (stale.getTransaction().isActive()) stale.getTransaction().rollback();
            first.close();
            stale.close();
        }
    }

    private ApplicationView save(Job job) {
        return tracker.transition(job.getId(), ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND).application();
    }

    private Job job(String key) {
        Instant now = Instant.parse("2026-07-19T09:00:00Z");
        return jobs.saveAndFlush(new Job("test", key, "https://example.test/jobs/" + key,
                "Java <Intern> " + key, "Example & Co", "Bucharest", RemoteType.HYBRID,
                "Internship", "Java internship", now, null, "a".repeat(64), "b".repeat(64),
                Integer.toHexString(key.hashCode()).repeat(8), now));
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock fixedClock() {
            return new MutableClock(NOW);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant current;

        MutableClock(Instant current) {
            this.current = current;
        }

        void set(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC test clock only");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
