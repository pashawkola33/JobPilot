package com.jobpilot.miniapp.repository;

import com.jobpilot.miniapp.domain.MiniAppState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MiniAppStateRepository extends JpaRepository<MiniAppState, Long> {

    /**
     * Taken as the first statement of every Mini App mutation and held until commit, which is
     * what makes revision order equal commit order rather than transaction-start order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MiniAppState s where s.id = :id and s.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    Optional<MiniAppState> findByIdForUpdate(@Param("id") short id);
    @Query("select s from MiniAppState s where s.id = :id and s.candidateId = (select c.id from Candidate c where c.stableKey = 'default')")
    Optional<MiniAppState> findById(short id);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "insert into mini_app_state(candidate_id,id,mutation_revision) values (:candidateId,1,0) on conflict(candidate_id) do nothing", nativeQuery = true)
    void ensureCandidate(long candidateId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MiniAppState s where s.candidateId = :candidateId")
    Optional<MiniAppState> findByCandidateIdForUpdate(long candidateId);
}
