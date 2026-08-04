package com.jobpilot.jobs.repository;

import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.matching.ScoreBand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.QueryHint;

public interface JobScoreRepository extends JpaRepository<JobScore, Long> {
    Optional<JobScore> findByJobId(Long jobId);

    List<JobScore> findByBandOrderByScoreDesc(ScoreBand band, Pageable pageable);

    @Query("select s from JobScore s where s.band = :band and s.scoredAt >= :since "
            + "and s.job.screeningDisposition = "
            + "com.jobpilot.jobs.domain.ScreeningDisposition.MATCH "
            + "order by s.score desc")
    List<JobScore> findDigest(ScoreBand band, Instant since, Pageable pageable);

    long countByBand(ScoreBand band);

    @Query("select count(s) from JobScore s where s.job.screeningDisposition in "
            + "(com.jobpilot.jobs.domain.ScreeningDisposition.MATCH, "
            + "com.jobpilot.jobs.domain.ScreeningDisposition.REVIEW)")
    long countRescorePreviewCandidates();

    @Query("select s from JobScore s join fetch s.job j "
            + "where j.screeningDisposition in "
            + "(com.jobpilot.jobs.domain.ScreeningDisposition.MATCH, "
            + "com.jobpilot.jobs.domain.ScreeningDisposition.REVIEW) order by j.id")
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    List<JobScore> findRescorePreviewCandidates(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from JobScore s join fetch s.job j where j.id in :jobIds order by j.id")
    List<JobScore> findAllByJobIdInForRescoreWrite(Collection<Long> jobIds);
}
