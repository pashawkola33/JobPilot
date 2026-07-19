package com.jobpilot.telegram.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface TelegramClient {
    List<TelegramUpdate> getUpdates(Long offset, Duration timeout, int limit);
    void sendMessage(String chatId, String html, List<List<Map<String, String>>> buttons);
    void answerCallbackQuery(String callbackQueryId, String text);
}
