package com.jobpilot.jobs.service;

import com.jobpilot.jobs.domain.RawJob;

final class RawJobIdentity {
    private RawJobIdentity() {
    }

    static String key(RawJob raw) {
        if (raw.url() != null && !raw.url().isBlank()) return "url:" + raw.url().strip();
        if (raw.externalId() != null && !raw.externalId().isBlank()) {
            return "external:" + safe(raw.source()) + ":" + safe(raw.providerTenant())
                    + ":" + raw.externalId().strip();
        }
        return "content:" + safe(raw.company()) + "|" + safe(raw.title()) + "|" + safe(raw.location());
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
