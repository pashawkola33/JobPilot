package com.jobpilot.telegram.api;

public record TelegramUpdate(long updateId, TelegramMessage message, TelegramCallbackQuery callbackQuery) {
    public record TelegramMessage(long messageId, TelegramUser from, TelegramChat chat, String text) {
    }

    public record TelegramCallbackQuery(
            String id, TelegramUser from, TelegramMessage message, String data) {
    }

    public record TelegramUser(long id) {
    }

    public record TelegramChat(long id) {
    }
}
