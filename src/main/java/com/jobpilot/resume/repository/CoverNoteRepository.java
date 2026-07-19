package com.jobpilot.resume.repository;

import com.jobpilot.resume.domain.CoverNote;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
}
