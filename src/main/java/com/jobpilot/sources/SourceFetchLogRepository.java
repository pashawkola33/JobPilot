package com.jobpilot.sources;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceFetchLogRepository extends JpaRepository<SourceFetchLog, Long> {
    /**
     * Moves one row out of RUNNING, and only out of RUNNING.
     *
     * <p>The {@code status = 'RUNNING'} predicate is what makes terminalization safe to
     * retry and impossible to use for overwriting an existing terminal status: a second
     * attempt simply updates zero rows.
     *
     * @return 1 when this call performed the transition, 0 when it did not.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SourceFetchLog log
               set log.status = :status,
                   log.finishedAt = :finishedAt,
                   log.fetchedCount = :fetched,
                   log.savedCount = :saved,
                   log.errorSummary = :errorSummary
             where log.id = :id
               and log.status = 'RUNNING'
            """)
    int terminalize(@Param("id") long id,
                    @Param("status") String status,
                    @Param("finishedAt") Instant finishedAt,
                    @Param("fetched") int fetched,
                    @Param("saved") int saved,
                    @Param("errorSummary") String errorSummary);
}
