package com.jobpilot.telegram.commands;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramCallbackQuery;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramChat;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramMessage;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramUser;
import org.springframework.stereotype.Component;

/**
 * Numeric authorization only. A Telegram username is user-controlled and reassignable,
 * so it is never consulted. Messages and callbacks are checked independently.
 */
@Component
public class TelegramAuthorizationPolicy {
    private final String allowedChatId;
    private final String allowedUserId;
    private final JobPilotProperties.Telegram settings;

    public TelegramAuthorizationPolicy(JobPilotProperties properties) {
        this.settings = properties.telegram();
        this.allowedChatId = settings.allowedChatId();
        this.allowedUserId = settings.allowedUserId();
    }

    public boolean authorized(TelegramMessage message) {
        return message != null && authorized(message.chat(), message.from());
    }

    public boolean authorized(TelegramCallbackQuery callback) {
        return callback != null && callback.message() != null
                && authorized(callback.message().chat(), callback.from());
    }

    private boolean authorized(TelegramChat chat, TelegramUser from) {
        if (chat == null || from == null) return false;
        return legacyPair(chat, from) || privateReviewChat(chat, from);
    }

    /** Phase 3 application-tracking surface: one explicit chat plus one explicit user. */
    private boolean legacyPair(TelegramChat chat, TelegramUser from) {
        return allowedChatId != null && !allowedChatId.isBlank()
                && allowedChatId.equals(Long.toString(chat.id()))
                && allowedUserId != null && allowedUserId.equals(Long.toString(from.id()));
    }

    /**
     * Review bot: the chat must be an allowed id and must be a private chat. In a Telegram
     * private chat the chat id equals the user id, so a group or channel can never match.
     */
    private boolean privateReviewChat(TelegramChat chat, TelegramUser from) {
        return settings.enabled() && chat.id() == from.id() && settings.allowsChat(chat.id());
    }
}
