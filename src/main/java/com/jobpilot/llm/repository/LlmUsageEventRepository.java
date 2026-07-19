package com.jobpilot.llm.repository;

import com.jobpilot.llm.domain.LlmUsageEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface LlmUsageEventRepository extends JpaRepository<LlmUsageEvent, Long> {
    boolean existsByReservationId(Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from LlmUsageEvent event where event.reservation.id = :reservationId")
    Optional<LlmUsageEvent> findByReservationIdForUpdate(Long reservationId);

    @Query("select coalesce(sum(event.estimatedCostUsd), 0) from LlmUsageEvent event "
            + "where event.createdAt >= :startInclusive and event.createdAt < :endExclusive")
    BigDecimal sumEstimatedCostBetween(Instant startInclusive, Instant endExclusive);
}
