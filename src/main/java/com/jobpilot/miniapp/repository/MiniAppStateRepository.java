package com.jobpilot.miniapp.repository;

import com.jobpilot.miniapp.domain.MiniAppState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MiniAppStateRepository extends JpaRepository<MiniAppState, Short> {

    /**
     * Taken as the first statement of every Mini App mutation and held until commit, which is
     * what makes revision order equal commit order rather than transaction-start order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MiniAppState s where s.id = :id")
    Optional<MiniAppState> findByIdForUpdate(@Param("id") short id);
}
