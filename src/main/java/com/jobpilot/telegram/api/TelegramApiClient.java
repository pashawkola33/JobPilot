package com.jobpilot.telegram.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramCallbackQuery;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramChat;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramMessage;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramUser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TelegramApiClient implements TelegramClient {
    private final ExternalHttpClient http;
    private final JobPilotProperties.Telegram settings;

    public TelegramApiClient(ExternalHttpClient http, JobPilotProperties properties) {
        this.http = http;
        this.settings = properties.telegram();
    }

    @Override
    public List<TelegramUpdate> getUpdates(Long offset, Duration timeout, int limit) {
        var body = new LinkedHashMap<String, Object>();
        if (offset != null) body.put("offset", offset);
        body.put("timeout", timeout.toSeconds());
        body.put("limit", limit);
        body.put("allowed_updates", List.of("message", "callback_query"));
        JsonNode response = call("getUpdates", body, TelegramTransportException.Operation.GET_UPDATES);
        JsonNode result = validResult(response, TelegramTransportException.Operation.GET_UPDATES);
        if (!result.isArray()) throw new TelegramTransportException(
                TelegramTransportException.Operation.GET_UPDATES);
        List<TelegramUpdate> updates = new ArrayList<>();
        for (JsonNode item : result) updates.add(toUpdate(item));
        return updates;
    }

    @Override
    public void sendMessage(String chatId, String html,
                            List<List<Map<String, String>>> buttons) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (buttons != null && !buttons.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", buttons));
        }
        validResult(call("sendMessage", body, TelegramTransportException.Operation.SEND_MESSAGE),
                TelegramTransportException.Operation.SEND_MESSAGE);
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId, String text) {
        Map<String, Object> body = Map.of(
                "callback_query_id", callbackQueryId,
                "text", text == null ? "Done" : text.substring(0, Math.min(200, text.length())));
        validResult(call("answerCallbackQuery", body,
                        TelegramTransportException.Operation.ANSWER_CALLBACK_QUERY),
                TelegramTransportException.Operation.ANSWER_CALLBACK_QUERY);
    }

    private JsonNode call(String method, Object body, TelegramTransportException.Operation operation) {
        try {
            return http.postJson(endpoint(method), body);
        } catch (RuntimeException unsafe) {
            // HTTP exceptions can contain the complete URL, including the bot token.
            throw new TelegramTransportException(operation);
        }
    }

    private JsonNode validResult(JsonNode response, TelegramTransportException.Operation operation) {
        if (response == null || !response.path("ok").asBoolean(false) || !response.has("result")) {
            throw new TelegramTransportException(operation);
        }
        return response.get("result");
    }

    private TelegramUpdate toUpdate(JsonNode node) {
        long id = requiredLong(node, "update_id");
        TelegramMessage message = node.has("message") ? toMessage(node.get("message")) : null;
        TelegramCallbackQuery callback = node.has("callback_query")
                ? toCallback(node.get("callback_query")) : null;
        return new TelegramUpdate(id, message, callback);
    }

    private TelegramCallbackQuery toCallback(JsonNode node) {
        String id = requiredText(node, "id");
        TelegramUser from = toUser(node.get("from"));
        TelegramMessage message = node.has("message") ? toMessage(node.get("message")) : null;
        String data = node.hasNonNull("data") ? node.get("data").asText() : null;
        return new TelegramCallbackQuery(id, from, message, data);
    }

    private TelegramMessage toMessage(JsonNode node) {
        long messageId = requiredLong(node, "message_id");
        TelegramUser from = node.has("from") ? toUser(node.get("from")) : null;
        TelegramChat chat = node.has("chat")
                ? new TelegramChat(requiredLong(node.get("chat"), "id")) : null;
        String text = node.hasNonNull("text") ? node.get("text").asText() : null;
        return new TelegramMessage(messageId, from, chat, text);
    }

    private TelegramUser toUser(JsonNode node) {
        return node == null ? null : new TelegramUser(requiredLong(node, "id"));
    }

    private long requiredLong(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).canConvertToLong()) {
            throw new TelegramTransportException(TelegramTransportException.Operation.GET_UPDATES);
        }
        return node.get(field).longValue();
    }

    private String requiredText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new TelegramTransportException(TelegramTransportException.Operation.GET_UPDATES);
        }
        return node.get(field).asText();
    }

    private String endpoint(String method) {
        return "https://api.telegram.org/bot" + settings.botToken() + "/" + method;
    }
}
