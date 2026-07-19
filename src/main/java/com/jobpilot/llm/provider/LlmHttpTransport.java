package com.jobpilot.llm.provider;

import java.net.URI;
import java.time.Duration;

public interface LlmHttpTransport {
    LlmHttpResponse post(URI endpoint, String apiKey, String jsonBody,
                         Duration responseTimeout, int maxResponseBytes);
}
