package com.jobpilot.manualurl.fetch;

import java.net.URI;

public record ManualFetchedResource(
        URI requestedUri,
        URI finalUri,
        String contentType,
        String body) {
}
