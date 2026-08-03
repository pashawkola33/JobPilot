package com.jobpilot.telegram.commands;

import java.util.List;
import java.util.Map;

public record TelegramCommandResult(String html, String callbackText,
                                    List<List<Map<String, String>>> buttons) {
    public TelegramCommandResult {
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    public TelegramCommandResult(String html, String callbackText) {
        this(html, callbackText, List.of());
    }
}
