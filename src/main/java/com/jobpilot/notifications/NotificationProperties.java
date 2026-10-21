package com.jobpilot.notifications;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("jobpilot.notifications")
public record NotificationProperties(boolean enabled) { }
