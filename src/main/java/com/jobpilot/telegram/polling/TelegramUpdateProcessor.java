package com.jobpilot.telegram.polling;

import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.telegram.api.TelegramClient;
import com.jobpilot.telegram.api.TelegramTransportException;
import com.jobpilot.telegram.api.TelegramUpdate;
import com.jobpilot.telegram.commands.TelegramAuthorizationPolicy;
import com.jobpilot.telegram.commands.TelegramCommand;
import com.jobpilot.telegram.commands.TelegramCommandDispatcher;
import com.jobpilot.telegram.commands.TelegramCommandParseResult;
import com.jobpilot.telegram.commands.TelegramCommandParser;
import com.jobpilot.telegram.commands.TelegramCommandResult;
import com.jobpilot.telegram.commands.TelegramMessageRenderer;
import com.jobpilot.telegram.review.TelegramCallbackData;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TelegramUpdateProcessor {
    private static final Pattern CALLBACK = Pattern.compile("app:(save|applied):([1-9]\\d{0,18})");
    private final TelegramClient client;
    private final TelegramAuthorizationPolicy authorization;
    private final TelegramCommandParser parser;
    private final TelegramCommandDispatcher dispatcher;
    private final TelegramMessageRenderer renderer;

    public TelegramUpdateProcessor(TelegramClient client,
                                   TelegramAuthorizationPolicy authorization,
                                   TelegramCommandParser parser,
                                   TelegramCommandDispatcher dispatcher,
                                   TelegramMessageRenderer renderer) {
        this.client = client;
        this.authorization = authorization;
        this.parser = parser;
        this.dispatcher = dispatcher;
        this.renderer = renderer;
    }

    public void process(TelegramUpdate update) {
        if (update.message() != null) {
            processMessage(update);
        } else if (update.callbackQuery() != null) {
            processCallback(update);
        }
    }

    private void processMessage(TelegramUpdate update) {
        // Authorized independently of any callback check.
        if (!authorization.authorized(update.message())) return;
        long chatId = update.message().chat().id();
        TelegramCommandParseResult parsed = parser.parse(update.message().text());
        if (!parsed.valid()) {
            safeSend(chatId, renderer.error(parsed.error()), List.of());
            return;
        }
        if (parsed.command().kind() == TelegramCommand.Kind.ANALYZE
                || parsed.command().kind() == TelegramCommand.Kind.DOCUMENTS) {
            safeSend(chatId, renderer.progress(parsed.command().kind()), List.of());
        }
        TelegramCommandResult result = dispatcher.dispatch(
                parsed.command(), ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        // Any application mutation has committed before this best-effort reply begins.
        safeSend(chatId, result.html(), result.buttons());
    }

    private void processCallback(TelegramUpdate update) {
        var callback = update.callbackQuery();
        // Authorized independently of the message path; a stale keyboard cannot bypass it.
        if (!authorization.authorized(callback)) return;
        long chatId = callback.message().chat().id();
        String answer = "Invalid action";
        RuntimeException internalFailure = null;
        try {
            answer = handleCallback(chatId, callback.data());
        } catch (RuntimeException failure) {
            answer = "Could not process action";
            internalFailure = failure;
        } finally {
            // Always acknowledge so the client stops showing a spinner.
            safeAnswer(callback.id(), answer);
        }
        if (internalFailure != null) throw internalFailure;
    }

    private String handleCallback(long chatId, String data) {
        TelegramCallbackData.ActionCommand action = TelegramCallbackData.parseAction(data);
        if (action != null) {
            TelegramCommandResult result = dispatcher.applyWorkflow(action.action(), action.jobId());
            safeSend(chatId, result.html(), List.of());
            return result.callbackText();
        }
        TelegramCallbackData.NextCommand next = TelegramCallbackData.parseNext(data);
        if (next != null) {
            TelegramCommandResult result = dispatcher.dispatch(
                    TelegramCommand.queue(next.queue(), next.page()),
                    ApplicationStatusChangeSource.TELEGRAM_CALLBACK);
            safeSend(chatId, result.html(), result.buttons());
            return result.callbackText();
        }
        TelegramCommand legacy = callbackCommand(data);
        if (legacy == null) return "Invalid action";
        TelegramCommandResult result = dispatcher.dispatch(
                legacy, ApplicationStatusChangeSource.TELEGRAM_CALLBACK);
        return result.callbackText();
    }

    private TelegramCommand callbackCommand(String data) {
        if (data == null || data.length() > 64) return null;
        Matcher matcher = CALLBACK.matcher(data);
        if (!matcher.matches()) return null;
        try {
            long jobId = Long.parseLong(matcher.group(2));
            TelegramCommand.Kind kind = matcher.group(1).equals("save")
                    ? TelegramCommand.Kind.SAVE : TelegramCommand.Kind.APPLIED;
            return new TelegramCommand(kind, jobId, null, null, null, null);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void safeSend(long chatId, String html, List<List<Map<String, String>>> buttons) {
        try {
            client.sendMessage(Long.toString(chatId), html, buttons);
        } catch (TelegramTransportException ignored) {
            // The command is complete; replaying it merely because confirmation failed is unsafe.
        }
    }

    private void safeAnswer(String callbackId, String text) {
        try {
            client.answerCallbackQuery(callbackId, text);
        } catch (TelegramTransportException ignored) {
            // Callback acknowledgement is best effort and must not repeat a committed mutation.
        }
    }
}
