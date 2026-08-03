package com.jobpilot.jobreview.application;

/** The four review queues the Telegram bot exposes. */
public enum JobQueue {
    MATCHES,
    REVIEW,
    SAVED,
    APPLIED;

    /** Compact, bounded token used inside Telegram callback data. */
    public String token() {
        return name().substring(0, 1).toLowerCase(java.util.Locale.ROOT);
    }

    public static JobQueue fromToken(String token) {
        for (JobQueue queue : values()) if (queue.token().equals(token)) return queue;
        return null;
    }
}
