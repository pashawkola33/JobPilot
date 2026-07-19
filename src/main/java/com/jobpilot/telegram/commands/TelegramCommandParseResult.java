package com.jobpilot.telegram.commands;

public record TelegramCommandParseResult(TelegramCommand command, String error) {
    public static TelegramCommandParseResult success(TelegramCommand command) {
        return new TelegramCommandParseResult(command, null);
    }

    public static TelegramCommandParseResult failure(String error) {
        return new TelegramCommandParseResult(null, error);
    }

    public boolean valid() {
        return command != null;
    }
}
