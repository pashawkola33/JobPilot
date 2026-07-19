package com.jobpilot.telegram.api;

public final class TelegramTransportException extends RuntimeException {
    public enum Operation { GET_UPDATES, SEND_MESSAGE, ANSWER_CALLBACK_QUERY }

    private final Operation operation;

    public TelegramTransportException(Operation operation) {
        super("Telegram transport failed during " + operation.name().toLowerCase());
        this.operation = operation;
    }

    public Operation getOperation() {
        return operation;
    }
}
