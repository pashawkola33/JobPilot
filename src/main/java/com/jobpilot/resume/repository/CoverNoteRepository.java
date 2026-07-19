package com.jobpilot.resume.repository;

import com.jobpilot.resume.domain.CoverNote;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import java.time.Instant;

public interface CoverNoteRepository extends JpaRepository<CoverNote, Long> {
    List<CoverNote> findByJobIdOrderByGeneratedAtDesc(Long jobId);

    Optional<CoverNote> findByCacheKey(String cacheKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select note from CoverNote note where note.cacheKey = :cacheKey")
    Optional<CoverNote> findByCacheKeyForUpdate(String cacheKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select note from CoverNote note where note.id = :id")
    Optional<CoverNote> findByIdForUpdate(long id);

    @Query("select note.docxPath from CoverNote note where note.docxPath is not null")
    List<String> findAllDocxPaths();

    @Query("select note.pdfPath from CoverNote note where note.pdfPath is not null")
    List<String> findAllPdfPaths();

    @Query("select note.id from CoverNote note where note.renderStatus = :status "
            + "and note.updatedAt <= :olderThan order by note.updatedAt asc, note.id asc")
    List<Long> findStaleIds(DocumentRenderStatus status, Instant olderThan, Pageable pageable);

    boolean existsByDocxPathOrPdfPath(String docxPath, String pdfPath);

    long countByRenderStatus(DocumentRenderStatus status);
}
