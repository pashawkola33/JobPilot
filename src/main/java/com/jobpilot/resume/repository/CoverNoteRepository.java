package com.jobpilot.resume.repository;

import com.jobpilot.resume.domain.CoverNote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoverNoteRepository extends JpaRepository<CoverNote, Long> {
    List<CoverNote> findByJobIdOrderByGeneratedAtDesc(Long jobId);
}
