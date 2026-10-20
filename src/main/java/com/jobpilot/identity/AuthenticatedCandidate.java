package com.jobpilot.identity;

import java.time.Instant;

/** Server-created session identity; services revalidate it before reading private account data. */
public record AuthenticatedCandidate(long candidateId, long telegramUserId,
                                     Instant expiresAt, String sessionHash) {
    @Override public String toString() { return "AuthenticatedCandidate[redacted]"; }
}
