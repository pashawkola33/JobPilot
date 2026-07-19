package com.jobpilot.llm.repository;

import com.jobpilot.llm.budget.LlmBudgetControl;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface LlmBudgetControlRepository extends JpaRepository<LlmBudgetControl, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select control from LlmBudgetControl control where control.id = :id")
    Optional<LlmBudgetControl> findByIdForUpdate(short id);
}
