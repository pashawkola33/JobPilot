package com.jobpilot.llm.provider;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class LlmSleeper {
    public void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new com.jobpilot.llm.api.LlmProviderException(
                    com.jobpilot.llm.domain.LlmFailureCategory.CONNECTION,
                    "LLM provider retry was interrupted");
        }
    }
}
