package com.jobpilot.jobreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.jobreview.application.JobQueue;
import com.jobpilot.jobreview.application.JobQueueItem;
import com.jobpilot.jobreview.application.JobQueuePage;
import com.jobpilot.jobreview.application.JobReviewException;
import com.jobpilot.jobreview.application.JobReviewService;
import com.jobpilot.jobreview.application.JobReviewStats;
import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.telegram.review.DeliveryType;
import com.jobpilot.telegram.review.TelegramJobDelivery;
import com.jobpilot.telegram.review.TelegramJobDeliveryRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Review queue behaviour against real PostgreSQL. Nothing here reaches Telegram or any ATS:
 * every vacancy is inserted directly and every assertion is local to the container.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class JobReviewWorkflowIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JobReviewService review;

    @Autowired
    private TelegramJobDeliveryRepository deliveries;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from telegram_job_delivery");
        jdbc.update("delete from job_workflow_state");
        jdbc.update("delete from job_scores");
        jdbc.update("delete from jobs");
    }

    /** Inserts one screened vacancy and, unless REJECT, its score. */
    private long job(String externalId, ScreeningDisposition disposition, String status,
                     Integer score, String publishedAt) {
        jdbc.update("""
                insert into jobs (source, provider_tenant, external_id, canonical_url, title,
                                  company, location, description, status, screening_disposition,
                                  fetched_at, first_seen_at, last_seen_at, published_at,
                                  raw_payload_hash, description_hash, normalized_fingerprint)
                values ('greenhouse', 'acme', ?, ?, ?, 'Acme', 'Bucharest', 'Description', ?, ?,
                        now(), now(), now(), ?::timestamptz, ?, ?, ?)
                """, externalId, "https://example.test/jobs/" + externalId, "Java Intern " + externalId,
                status, disposition.name(), publishedAt, externalId, externalId, externalId);
        Long id = jdbc.queryForObject("select id from jobs where external_id = ?", Long.class,
                externalId);
        if (score != null) {
            jdbc.update("""
                    insert into job_scores (job_id, score, band, suitable, formal_eligibility,
                                            java_backend, trainee_quality, supporting_technology,
                                            location_format, experience_compatibility, freshness,
                                            penalties, strengths, risks, hard_blockers, scored_at)
                    values (?, ?, 'EXCELLENT_MATCH', true, 1, 1, 1, 1, 1, 1, 1, 0, '', '', '', now())
                    """, id, score);
        }
        return id;
    }

    private long active(String externalId, ScreeningDisposition disposition, int score,
                        String publishedAt) {
        return job(externalId, disposition, "NEW", score, publishedAt);
    }

    private List<Long> ids(JobQueuePage page) {
        return page.items().stream().map(JobQueueItem::id).toList();
    }

    @Test
    void schemaEndsAtVersionTwelveWithBothReviewTables() {
        assertThat(jdbc.queryForObject(
                "select max(version::int) from flyway_schema_history where success", Integer.class))
                .isEqualTo(12);
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class)).contains("job_workflow_state", "telegram_job_delivery");
    }

    @Test
    void treatsAVacancyWithoutAWorkflowRowAsUnreviewed() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        assertThat(review.detail(id).workflowStatus()).isEqualTo(WorkflowStatus.UNREVIEWED);
        assertThat(jdbc.queryForObject("select count(*) from job_workflow_state", Integer.class))
                .isZero();
    }

    @Test
    void savesAppliesDismissesAndResetsOneVacancy() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        assertThat(review.save(id).status()).isEqualTo(WorkflowStatus.SAVED);
        assertThat(review.detail(id).workflowStatus()).isEqualTo(WorkflowStatus.SAVED);

        WorkflowView applied = review.applied(id);
        assertThat(applied.status()).isEqualTo(WorkflowStatus.APPLIED);
        assertThat(applied.appliedAt()).isNotNull();

        // Leaving APPLIED clears applied_at, matching the check constraint.
        assertThat(review.dismiss(id).appliedAt()).isNull();
        assertThat(review.reset(id).status()).isEqualTo(WorkflowStatus.UNREVIEWED);
        assertThat(jdbc.queryForObject("select count(*) from job_workflow_state", Integer.class))
                .isZero();
    }

    @Test
    void repeatingAnActionIsIdempotentAndReportsNoChange() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        assertThat(review.save(id).changed()).isTrue();
        assertThat(review.save(id).changed()).isFalse();
        assertThat(review.reset(id).changed()).isTrue();
        assertThat(review.reset(id).changed()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from job_workflow_state", Integer.class))
                .isZero();
    }

    @Test
    void keepsAppliedAtStableWhenAppliedIsRepeated() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        var first = review.applied(id);
        var second = review.applied(id);

        assertThat(second.changed()).isFalse();
        assertThat(second.appliedAt()).isEqualTo(first.appliedAt());
    }

    @Test
    void addsReplacesAndClearsANote() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        // A note on an untriaged vacancy also saves it, since the note needs a row.
        assertThat(review.note(id, "  first note  ")).satisfies(view -> {
            assertThat(view.note()).isEqualTo("first note");
            assertThat(view.status()).isEqualTo(WorkflowStatus.SAVED);
        });
        assertThat(review.note(id, "second note").note()).isEqualTo("second note");
        assertThat(review.note(id, null).note()).isNull();
        assertThat(review.detail(id).workflowStatus()).isEqualTo(WorkflowStatus.SAVED);
    }

    @Test
    void clearingANoteOnAnUntriagedVacancyChangesNothing() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        assertThat(review.note(id, "   ").changed()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from job_workflow_state", Integer.class))
                .isZero();
    }

    @Test
    void keepsTheNoteWhenTheStatusChanges() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");
        review.note(id, "call them back");

        assertThat(review.applied(id).note()).isEqualTo("call them back");
    }

    @Test
    void rejectsANoteLongerThanTheDatabaseCeiling() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        assertThatThrownBy(() -> review.note(id, "n".repeat(1001)))
                .isInstanceOf(JobReviewException.class)
                .hasMessageContaining("at most 1000");
    }

    @Test
    void rejectsWorkflowChangesForAVacancyThatDoesNotExist() {
        for (long missing : new long[] {0L, -1L, 999_999L}) {
            assertThatThrownBy(() -> review.save(missing))
                    .isInstanceOf(JobReviewException.class)
                    .hasMessageContaining("not in the review queue");
        }
    }

    @Test
    void keepsWorkflowStateWhenIngestionUpdatesTheVacancy() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");
        review.note(id, "keep me");
        review.applied(id);

        // Simulates the re-ingestion path: same row, refreshed provider fields.
        jdbc.update("update jobs set title = ?, description = ?, last_seen_at = now() where id = ?",
                "Java Intern (updated)", "New description", id);

        assertThat(review.detail(id)).satisfies(detail -> {
            assertThat(detail.title()).isEqualTo("Java Intern (updated)");
            assertThat(detail.workflowStatus()).isEqualTo(WorkflowStatus.APPLIED);
            assertThat(detail.note()).isEqualTo("keep me");
            assertThat(detail.appliedAt()).isNotNull();
        });
    }

    @Test
    void separatesTheMatchAndReviewQueuesAndExcludesRejectedVacancies() {
        long match = active("m", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");
        long reviewJob = active("r", ScreeningDisposition.REVIEW, 60, "2026-08-01T10:00:00Z");
        job("x", ScreeningDisposition.REJECT, "NEW", null, "2026-08-01T10:00:00Z");

        assertThat(ids(review.page(JobQueue.MATCHES, 0, 20))).containsExactly(match);
        assertThat(ids(review.page(JobQueue.REVIEW, 0, 20))).containsExactly(reviewJob);
        assertThat(jdbc.queryForObject(
                "select count(*) from jobs j left join job_scores s on s.job_id = j.id "
                        + "where j.screening_disposition = 'REJECT' and s.job_id is not null",
                Integer.class)).isZero();
    }

    @Test
    void excludesExpiredVacanciesFromEveryQueue() {
        long expired = job("e", ScreeningDisposition.MATCH, "EXPIRED", 95, "2026-08-01T10:00:00Z");
        review.save(expired);

        assertThat(review.page(JobQueue.MATCHES, 0, 20).items()).isEmpty();
        assertThat(review.page(JobQueue.SAVED, 0, 20).items()).isEmpty();
        assertThat(review.stats().saved()).isZero();
    }

    @Test
    void hidesDismissedVacanciesFromTheTriageQueuesButKeepsTheirRow() {
        long dismissed = active("d", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");
        long visible = active("v", ScreeningDisposition.MATCH, 80, "2026-08-01T10:00:00Z");
        review.dismiss(dismissed);

        assertThat(ids(review.page(JobQueue.MATCHES, 0, 20))).containsExactly(visible);
        assertThat(review.stats().dismissed()).isEqualTo(1);
        assertThat(review.detail(dismissed).workflowStatus()).isEqualTo(WorkflowStatus.DISMISSED);
    }

    @Test
    void listsSavedAndAppliedQueuesRegardlessOfDisposition() {
        long savedMatch = active("sm", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");
        long savedReview = active("sr", ScreeningDisposition.REVIEW, 70, "2026-08-01T10:00:00Z");
        long appliedJob = active("ap", ScreeningDisposition.REVIEW, 65, "2026-08-01T10:00:00Z");
        review.save(savedMatch);
        review.save(savedReview);
        review.applied(appliedJob);

        assertThat(ids(review.page(JobQueue.SAVED, 0, 20)))
                .containsExactlyInAnyOrder(savedMatch, savedReview);
        assertThat(ids(review.page(JobQueue.APPLIED, 0, 20))).containsExactly(appliedJob);
    }

    @Test
    void ordersUnreviewedFirstThenSavedThenByScoreRecencyAndId() {
        long saved = active("saved", ScreeningDisposition.MATCH, 99, "2026-08-03T10:00:00Z");
        long topScore = active("top", ScreeningDisposition.MATCH, 95, "2026-08-01T10:00:00Z");
        long newer = active("newer", ScreeningDisposition.MATCH, 80, "2026-08-03T10:00:00Z");
        long older = active("older", ScreeningDisposition.MATCH, 80, "2026-08-01T10:00:00Z");
        review.save(saved);

        assertThat(ids(review.page(JobQueue.MATCHES, 0, 20)))
                .containsExactly(topScore, newer, older, saved);
    }

    @Test
    void breaksAFullTieOnJobIdDescendingForAStableOrder() {
        long first = active("t1", ScreeningDisposition.MATCH, 80, "2026-08-01T10:00:00Z");
        long second = active("t2", ScreeningDisposition.MATCH, 80, "2026-08-01T10:00:00Z");

        assertThat(ids(review.page(JobQueue.MATCHES, 0, 20)))
                .containsExactly(Math.max(first, second), Math.min(first, second));
        assertThat(ids(review.page(JobQueue.MATCHES, 0, 20)))
                .isEqualTo(ids(review.page(JobQueue.MATCHES, 0, 20)));
    }

    @Test
    void paginatesWithBoundedPagesAndReportsWhetherMoreRemain() {
        for (int index = 0; index < 5; index++) {
            active("p" + index, ScreeningDisposition.MATCH, 90 - index, "2026-08-01T10:00:00Z");
        }

        JobQueuePage first = review.page(JobQueue.MATCHES, 0, 2);
        JobQueuePage last = review.page(JobQueue.MATCHES, 2, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.hasNext()).isTrue();
        assertThat(last.items()).hasSize(1);
        assertThat(last.hasNext()).isFalse();
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(last));
    }

    @Test
    void clampsOversizedAndNegativePaginationRequests() {
        active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        assertThat(review.page(JobQueue.MATCHES, -5, 5000).size())
                .isEqualTo(JobReviewService.MAX_PAGE_SIZE);
        assertThat(review.page(JobQueue.MATCHES, -5, 5000).page()).isZero();
        assertThat(review.page(JobQueue.MATCHES, 99_999, 5).page())
                .isEqualTo(JobReviewService.MAX_PAGE_INDEX);
    }

    @Test
    void countsEveryQueueInTheStatistics() {
        active("m1", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");
        active("r1", ScreeningDisposition.REVIEW, 60, "2026-08-01T10:00:00Z");
        active("r2", ScreeningDisposition.REVIEW, 61, "2026-08-01T10:00:00Z");
        job("x", ScreeningDisposition.REJECT, "NEW", null, "2026-08-01T10:00:00Z");
        review.save(active("s1", ScreeningDisposition.MATCH, 88, "2026-08-01T10:00:00Z"));
        review.applied(active("a1", ScreeningDisposition.MATCH, 87, "2026-08-01T10:00:00Z"));
        review.dismiss(active("d1", ScreeningDisposition.MATCH, 86, "2026-08-01T10:00:00Z"));

        JobReviewStats stats = review.stats();

        assertThat(stats).isEqualTo(new JobReviewStats(1, 2, 1, 1, 1));
    }

    @Test
    void exposesNewlyIngestedMatchesToTheNotificationFlow() {
        long match = active("m", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");
        long dismissed = active("d", ScreeningDisposition.MATCH, 91, "2026-08-01T10:00:00Z");
        long expired = job("e", ScreeningDisposition.MATCH, "EXPIRED", 92, "2026-08-01T10:00:00Z");
        long rejected = job("x", ScreeningDisposition.REJECT, "NEW", null, "2026-08-01T10:00:00Z");
        review.dismiss(dismissed);

        List<Long> notifiable = review.notifiable(ScreeningDisposition.MATCH,
                        List.of(match, dismissed, expired, rejected)).stream()
                .map(JobQueueItem::id).toList();

        assertThat(notifiable).containsExactly(match);
    }

    @Test
    void recordsOneDeliveryPerChatJobAndTypeAndRejectsDuplicates() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");
        var now = java.time.Instant.parse("2026-08-04T09:00:00Z");
        deliveries.saveAndFlush(new TelegramJobDelivery(777, id, DeliveryType.MATCH_NOTIFICATION, now));
        deliveries.saveAndFlush(new TelegramJobDelivery(777, id, DeliveryType.REVIEW_DIGEST_ITEM, now));
        deliveries.saveAndFlush(new TelegramJobDelivery(888, id, DeliveryType.MATCH_NOTIFICATION, now));

        assertThat(deliveries.findDeliveredJobIds(777, DeliveryType.MATCH_NOTIFICATION, List.of(id)))
                .containsExactly(id);
        assertThat(deliveries.findDeliveredJobIds(999, DeliveryType.MATCH_NOTIFICATION, List.of(id)))
                .isEmpty();

        // Asserted last: a failed flush leaves the persistence context unusable.
        assertThatThrownBy(() -> deliveries.saveAndFlush(
                new TelegramJobDelivery(777, id, DeliveryType.MATCH_NOTIFICATION, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leavesAVacancyRetryableUntilADeliveryRowExists() {
        long id = active("a", ScreeningDisposition.MATCH, 90, "2026-08-01T10:00:00Z");

        assertThat(deliveries.existsByChatIdAndJobIdAndDeliveryType(
                777, id, DeliveryType.MATCH_NOTIFICATION)).isFalse();

        deliveries.saveAndFlush(new TelegramJobDelivery(777, id, DeliveryType.MATCH_NOTIFICATION,
                java.time.Instant.parse("2026-08-04T09:00:00Z")));

        assertThat(deliveries.existsByChatIdAndJobIdAndDeliveryType(
                777, id, DeliveryType.MATCH_NOTIFICATION)).isTrue();
    }
}
