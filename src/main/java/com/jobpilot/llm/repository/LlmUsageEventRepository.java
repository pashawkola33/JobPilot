package com.jobpilot.llm.repository;

import com.jobpilot.llm.domain.LlmUsageEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LlmUsageEventRepository extends JpaRepository<LlmUsageEvent, Long> {
    @Query("select coalesce(sum(event.estimatedCostUsd), 0) from LlmUsageEvent event "
            + "where event.createdAt >= :startInclusive and event.createdAt < :endExclusive")
    BigDecimal sumEstimatedCostBetween(Instant startInclusive, Instant endExclusive);
}
