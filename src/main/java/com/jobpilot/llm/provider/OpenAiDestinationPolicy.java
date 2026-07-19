package com.jobpilot.llm.provider;

import com.jobpilot.common.net.ProhibitedAddressClassifier;
import com.jobpilot.llm.api.LlmProviderException;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.manualurl.fetch.HostResolver;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OpenAiDestinationPolicy {
    static final String OFFICIAL_HOST = "api.openai.com";
    private final HostResolver resolver;

    public OpenAiDestinationPolicy(HostResolver resolver) {
        this.resolver = resolver;
    }

    public void validateBeforeAuthorization(URI endpoint) {
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || !OFFICIAL_HOST.equalsIgnoreCase(endpoint.getHost())
                || endpoint.getPort() != -1 && endpoint.getPort() != 443
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !"/v1/responses".equals(endpoint.getRawPath())) {
            throw prohibited();
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(endpoint.getHost());
        } catch (UnknownHostException resolutionFailure) {
            throw safe(LlmFailureCategory.CONNECTION,
                    "LLM provider destination could not be resolved");
        }
        if (addresses.isEmpty() || addresses.stream()
                .anyMatch(ProhibitedAddressClassifier::isProhibited)) {
            throw prohibited();
        }
    }

    private LlmProviderException prohibited() {
        return safe(LlmFailureCategory.CONFIGURATION,
                "LLM provider destination is not permitted");
    }

    private LlmProviderException safe(LlmFailureCategory category, String message) {
        return new LlmProviderException(category, message, 0);
    }
}
