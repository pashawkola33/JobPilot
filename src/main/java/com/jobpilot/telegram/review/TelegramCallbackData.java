package com.jobpilot.telegram.review;

import com.jobpilot.jobreview.application.JobQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single definition of review callback payloads, shared by the formatter that emits
 * them and the processor that parses them.
 *
 * <p>Payloads carry an action letter plus one bounded integer and nothing else: no title,
 * description, URL, note, token, or chat id ever reaches callback data. The longest
 * possible payload is 24 bytes, well inside the Bot API's 64-byte limit.
 */
public final class TelegramCallbackData {
    public static final int MAX_LENGTH = 64;

    public enum Action { SAVE, APPLIED, DISMISS, RESET }

    private static final Pattern ACTION = Pattern.compile("jr:([sadr]):([1-9]\\d{0,18})");
    private static final Pattern NEXT = Pattern.compile("jn:([mrsa]):(\\d{1,3})");

    private TelegramCallbackData() {
    }

    public static String action(Action action, long jobId) {
        return "jr:" + letter(action) + ":" + jobId;
    }

    public static String next(JobQueue queue, int page) {
        return "jn:" + queue.token() + ":" + page;
    }

    /** Returns null for anything that is not an exact, in-range action payload. */
    public static ActionCommand parseAction(String data) {
        if (data == null || data.length() > MAX_LENGTH) return null;
        Matcher matcher = ACTION.matcher(data);
        if (!matcher.matches()) return null;
        try {
            return new ActionCommand(action(matcher.group(1)), Long.parseLong(matcher.group(2)));
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    /** Returns null for anything that is not an exact, in-range pagination payload. */
    public static NextCommand parseNext(String data) {
        if (data == null || data.length() > MAX_LENGTH) return null;
        Matcher matcher = NEXT.matcher(data);
        if (!matcher.matches()) return null;
        JobQueue queue = JobQueue.fromToken(matcher.group(1));
        if (queue == null) return null;
        return new NextCommand(queue, Integer.parseInt(matcher.group(2)));
    }

    public record ActionCommand(Action action, long jobId) {
    }

    public record NextCommand(JobQueue queue, int page) {
    }

    private static String letter(Action action) {
        return switch (action) {
            case SAVE -> "s";
            case APPLIED -> "a";
            case DISMISS -> "d";
            case RESET -> "r";
        };
    }

    private static Action action(String letter) {
        return switch (letter) {
            case "s" -> Action.SAVE;
            case "a" -> Action.APPLIED;
            case "d" -> Action.DISMISS;
            default -> Action.RESET;
        };
    }
}
