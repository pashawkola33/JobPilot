package com.jobpilot.manualurl.fetch;

import java.net.URI;
import java.time.Duration;

@FunctionalInterface
public interface ManualHttpTransport {
    ManualHttpResponse get(URI uri, Duration responseTimeout, int maxResponseBytes);
}
