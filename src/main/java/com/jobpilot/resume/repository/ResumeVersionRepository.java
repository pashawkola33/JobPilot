package com.jobpilot.resume.repository;

import com.jobpilot.resume.domain.ResumeVersion;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {
    List<ResumeVersion> findByJobIdOrderByGeneratedAtDesc(Long jobId);

    Optional<ResumeVersion> findByCacheKey(String cacheKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select resume from ResumeVersion resume where resume.cacheKey = :cacheKey")
    Optional<ResumeVersion> findByCacheKeyForUpdate(String cacheKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select resume from ResumeVersion resume where resume.id = :id")
    Optional<ResumeVersion> findByIdForUpdate(long id);

    @Query("select resume.docxPath from ResumeVersion resume where resume.docxPath is not null")
    List<String> findAllDocxPaths();

    @Query("select resume.pdfPath from ResumeVersion resume where resume.pdfPath is not null")
    List<String> findAllPdfPaths();
}
