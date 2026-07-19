package com.jobpilot.maintenance;

import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentFailureCategory;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import com.jobpilot.resume.storage.DocumentArtifactStorage;
import com.jobpilot.resume.storage.DocumentKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(DocumentMaintenanceService.class);
    private final ResumeVersionRepository resumes;
    private final CoverNoteRepository coverNotes;
    private final DocumentArtifactStorage storage;
    private final DocumentProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public DocumentMaintenanceService(ResumeVersionRepository resumes,
                                      CoverNoteRepository coverNotes,
                                      DocumentArtifactStorage storage,
                                      DocumentProperties properties, Clock clock,
                                      PlatformTransactionManager transactionManager) {
        this.resumes = resumes;
        this.coverNotes = coverNotes;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public DocumentMaintenanceResult run(int maxItems, Duration maxDuration,
                                         Duration orphanGracePeriod) {
        if (!properties.enabled()) return DocumentMaintenanceResult.empty();
        requireBounds(maxItems, maxDuration, orphanGracePeriod);
        long deadline = System.nanoTime() + maxDuration.toNanos();
        Instant now = clock.instant();
        Instant staleBefore = now.minus(properties.staleAfter());
        int staleResumes = 0;
        int staleCovers = 0;
        int partials = 0;
        int orphans = 0;

        var resumeIds = resumes.findStaleIds(DocumentRenderStatus.IN_PROGRESS, staleBefore,
                PageRequest.of(0, maxItems));
        for (Long id : resumeIds) {
            if (used(staleResumes, staleCovers, partials, orphans) >= maxItems
                    || System.nanoTime() >= deadline) break;
            if (recoverResume(id, now)) staleResumes++;
        }

        int remaining = maxItems - used(staleResumes, staleCovers, partials, orphans);
        if (remaining > 0 && System.nanoTime() < deadline) {
            var coverIds = coverNotes.findStaleIds(DocumentRenderStatus.IN_PROGRESS, staleBefore,
                    PageRequest.of(0, remaining));
            for (Long id : coverIds) {
                if (used(staleResumes, staleCovers, partials, orphans) >= maxItems
                        || System.nanoTime() >= deadline) break;
                if (recoverCover(id, now)) staleCovers++;
            }
        }

        remaining = maxItems - used(staleResumes, staleCovers, partials, orphans);
        Duration time = remaining(deadline);
        if (remaining > 0 && time != null) {
            try {
                partials = storage.cleanupPartials(now.minus(orphanGracePeriod), remaining, time);
            } catch (RuntimeException failure) {
                log.warn("Document maintenance isolated failure category=partial_cleanup");
            }
        }

        remaining = maxItems - used(staleResumes, staleCovers, partials, orphans);
        time = remaining(deadline);
        if (remaining > 0 && time != null) {
            try {
                orphans = storage.cleanupOrphans(this::isReferenced,
                        now.minus(orphanGracePeriod), remaining, time);
            } catch (RuntimeException failure) {
                log.warn("Document maintenance isolated failure category=orphan_cleanup");
            }
        }
        return new DocumentMaintenanceResult(staleResumes, staleCovers, partials, orphans);
    }

    private boolean recoverResume(long id, Instant now) {
        try {
            Boolean changed = transactions.execute(status -> resumes.findByIdForUpdate(id)
                    .map(value -> {
                        if (!value.isStale(now, properties.staleAfter())) return false;
                        value.fail(DocumentFailureCategory.STALE_GENERATION, now);
                        return true;
                    }).orElse(false));
            if (!Boolean.TRUE.equals(changed)) return false;
            try {
                storage.deleteGeneratedBundle(DocumentKind.RESUME, id);
            } catch (RuntimeException failure) {
                log.warn("Document maintenance isolated failure documentId={} category=resume_cleanup", id);
            }
            return true;
        } catch (RuntimeException failure) {
            log.warn("Document maintenance isolated failure documentId={} category=resume_recovery", id);
            return false;
        }
    }

    private boolean recoverCover(long id, Instant now) {
        try {
            Boolean changed = transactions.execute(status -> coverNotes.findByIdForUpdate(id)
                    .map(value -> {
                        if (!value.isStale(now, properties.staleAfter())) return false;
                        value.fail(DocumentFailureCategory.STALE_GENERATION, now);
                        return true;
                    }).orElse(false));
            if (!Boolean.TRUE.equals(changed)) return false;
            try {
                storage.deleteGeneratedBundle(DocumentKind.COVER_NOTE, id);
            } catch (RuntimeException failure) {
                log.warn("Document maintenance isolated failure documentId={} category=cover_cleanup", id);
            }
            return true;
        } catch (RuntimeException failure) {
            log.warn("Document maintenance isolated failure documentId={} category=cover_recovery", id);
            return false;
        }
    }

    private boolean isReferenced(String relativePath) {
        return resumes.existsByDocxPathOrPdfPath(relativePath, relativePath)
                || coverNotes.existsByDocxPathOrPdfPath(relativePath, relativePath);
    }

    private int used(int resumes, int covers, int partials, int orphans) {
        return resumes + covers + partials + orphans;
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? null : Duration.ofNanos(nanos);
    }

    private void requireBounds(int maxItems, Duration maxDuration, Duration grace) {
        if (maxItems < 1 || maxItems > 1_000 || maxDuration == null
                || maxDuration.isZero() || maxDuration.isNegative()
                || maxDuration.compareTo(Duration.ofMinutes(5)) > 0
                || grace == null || grace.compareTo(Duration.ofMinutes(10)) < 0
                || grace.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Document maintenance bounds are invalid");
        }
    }
}
