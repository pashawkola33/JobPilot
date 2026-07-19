package com.jobpilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {
    @Bean
    RestClient restClient(JobPilotProperties properties) {
        JobPilotProperties.Http settings = properties.http();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.connectTimeout());
        Duration telegramReadTimeout = properties.telegram().pollTimeout().plusSeconds(5);
        factory.setReadTimeout(settings.responseTimeout().compareTo(telegramReadTimeout) >= 0
                ? settings.responseTimeout() : telegramReadTimeout);
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "JobPilot/0.1 (+human-in-the-loop job discovery)")
                .build();
    }
}
