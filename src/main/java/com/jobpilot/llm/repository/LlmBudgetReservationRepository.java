package com.jobpilot.llm.repository;

import com.jobpilot.llm.budget.LlmBudgetReservation;
import com.jobpilot.llm.budget.LlmBudgetReservationStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface LlmBudgetReservationRepository extends JpaRepository<LlmBudgetReservation, Long> {
    Optional<LlmBudgetReservation> findByRequestKey(String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from LlmBudgetReservation reservation where reservation.id = :id")
    Optional<LlmBudgetReservation> findByIdForUpdate(long id);

    List<LlmBudgetReservation> findFirst100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            LlmBudgetReservationStatus status, Instant now);

    @Query("select coalesce(sum(case when reservation.status = "
            + "com.jobpilot.llm.budget.LlmBudgetReservationStatus.RESERVED "
            + "then reservation.reservedCostUsd else reservation.finalCostUsd end), 0) "
            + "from LlmBudgetReservation reservation where reservation.budgetDay = :day")
    BigDecimal committedForDay(LocalDate day);

    @Query("select coalesce(sum(case when reservation.status = "
            + "com.jobpilot.llm.budget.LlmBudgetReservationStatus.RESERVED "
            + "then reservation.reservedCostUsd else reservation.finalCostUsd end), 0) "
            + "from LlmBudgetReservation reservation where reservation.budgetMonth = :month")
    BigDecimal committedForMonth(LocalDate month);
}
