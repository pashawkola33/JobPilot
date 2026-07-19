package com.jobpilot.telegram.commands;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramCallbackQuery;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramMessage;
import org.springframework.stereotype.Component;

@Component
public class TelegramAuthorizationPolicy {
    private final String allowedChatId;
    private final String allowedUserId;

    public TelegramAuthorizationPolicy(JobPilotProperties properties) {
        this.allowedChatId = properties.telegram().allowedChatId();
        this.allowedUserId = properties.telegram().allowedUserId();
    }

    public boolean authorized(TelegramMessage message) {
        return message != null && message.chat() != null && message.from() != null
                && allowedChatId.equals(Long.toString(message.chat().id()))
                && allowedUserId.equals(Long.toString(message.from().id()));
    }

    public boolean authorized(TelegramCallbackQuery callback) {
        return callback != null && callback.message() != null && callback.message().chat() != null
                && callback.from() != null
                && allowedChatId.equals(Long.toString(callback.message().chat().id()))
                && allowedUserId.equals(Long.toString(callback.from().id()));
    }
}
