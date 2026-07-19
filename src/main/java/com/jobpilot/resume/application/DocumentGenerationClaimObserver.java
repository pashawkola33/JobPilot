package com.jobpilot.resume.application;

import com.jobpilot.resume.storage.DocumentKind;
import org.springframework.stereotype.Component;

/** Synchronous no-op observer used to coordinate deterministic claim-lifecycle verification. */
@Component
public class DocumentGenerationClaimObserver {
    public void afterCacheMiss(DocumentKind kind, String opaqueCacheKey) {
        // Production intentionally has no side effect.
    }
}
