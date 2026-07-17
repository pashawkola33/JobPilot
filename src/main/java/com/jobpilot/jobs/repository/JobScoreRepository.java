package com.jobpilot.jobs.repository;

import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.matching.ScoreBand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobScoreRepository extends JpaRepository<JobScore, Long> {
    Optional<JobScore> findByJobId(Long jobId);

    List<JobScore> findByBandOrderByScoreDesc(ScoreBand band, Pageable pageable);

    @Query("select s from JobScore s where s.band = :band and s.scoredAt >= :since order by s.score desc")
    List<JobScore> findDigest(ScoreBand band, Instant since, Pageable pageable);

    long countByBand(ScoreBand band);
}
