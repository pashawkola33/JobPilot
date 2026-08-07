package com.jobpilot.applications.repository;

import com.jobpilot.applications.domain.ApplicationStatusHistory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ApplicationStatusHistoryRepository
        extends Repository<ApplicationStatusHistory, Long> {
    ApplicationStatusHistory save(ApplicationStatusHistory history);
    List<ApplicationStatusHistory> findByApplicationIdOrderByChangedAtAscIdAsc(Long applicationId);
    long countByApplicationId(Long applicationId);

    Optional<ApplicationStatusHistory> findById(Long id);

    /**
     * Removes one history row by identity. Mini App reversal only, and only the row the ledger
     * proves that mutation appended — never a row chosen by status, source or recency (I8).
     */
    void delete(ApplicationStatusHistory history);

    /** History is {@code @Immutable}, so a reversal must flush its delete before the parent. */
    void flush();

    /**
     * The newest history id for an application, or null when it has none.
     *
     * <p>One third of the reversal freshness fingerprint: every writer that appends history
     * moves this frontier, so an Undo issued before that write is deterministically stale
     * without consulting a clock.
     */
    @Query("select max(entry.id) from ApplicationStatusHistory entry "
            + "where entry.application.id = :applicationId")
    Long findFrontier(@Param("applicationId") Long applicationId);
}
