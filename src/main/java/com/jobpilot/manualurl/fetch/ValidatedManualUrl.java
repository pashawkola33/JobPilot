package com.jobpilot.manualurl.fetch;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

public record ValidatedManualUrl(URI uri, List<InetAddress> resolvedAddresses) {
    public ValidatedManualUrl {
        resolvedAddresses = List.copyOf(resolvedAddresses);
    }
}
