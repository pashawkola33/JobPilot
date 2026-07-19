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
import java.util.List;
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
    private final String allowedChatId;

    public TelegramUpdateProcessor(TelegramClient client,
                                   TelegramAuthorizationPolicy authorization,
                                   TelegramCommandParser parser,
                                   TelegramCommandDispatcher dispatcher,
                                   TelegramMessageRenderer renderer,
                                   JobPilotProperties properties) {
        this.client = client;
        this.authorization = authorization;
        this.parser = parser;
        this.dispatcher = dispatcher;
        this.renderer = renderer;
        this.allowedChatId = properties.telegram().allowedChatId();
    }

    public void process(TelegramUpdate update) {
        if (update.message() != null) {
            processMessage(update);
        } else if (update.callbackQuery() != null) {
            processCallback(update);
        }
    }

    private void processMessage(TelegramUpdate update) {
        if (!authorization.authorized(update.message())) return;
        TelegramCommandParseResult parsed = parser.parse(update.message().text());
        if (!parsed.valid()) {
            safeSend(renderer.error(parsed.error()));
            return;
        }
        if (parsed.command().kind() == TelegramCommand.Kind.ANALYZE
                || parsed.command().kind() == TelegramCommand.Kind.DOCUMENTS) {
            safeSend(renderer.progress(parsed.command().kind()));
        }
        TelegramCommandResult result = dispatcher.dispatch(
                parsed.command(), ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        // Any application mutation has committed before this best-effort reply begins.
        safeSend(result.html());
    }

    private void processCallback(TelegramUpdate update) {
        var callback = update.callbackQuery();
        if (!authorization.authorized(callback)) return;
        String answer = "Invalid action";
        RuntimeException internalFailure = null;
        try {
            TelegramCommand command = callbackCommand(callback.data());
            if (command != null) {
                TelegramCommandResult result = dispatcher.dispatch(
                        command, ApplicationStatusChangeSource.TELEGRAM_CALLBACK);
                answer = result.callbackText();
            }
        } catch (RuntimeException failure) {
            answer = "Could not process action";
            internalFailure = failure;
        } finally {
            safeAnswer(callback.id(), answer);
        }
        if (internalFailure != null) throw internalFailure;
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

    private void safeSend(String html) {
        try {
            client.sendMessage(allowedChatId, html, List.of());
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
