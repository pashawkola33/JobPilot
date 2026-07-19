package com.jobpilot.telegram.polling;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.api.TelegramClient;
import com.jobpilot.telegram.api.TelegramTransportException;
import com.jobpilot.telegram.api.TelegramUpdate;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramCallbackQuery;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramChat;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramMessage;
import com.jobpilot.telegram.api.TelegramUpdate.TelegramUser;
import com.jobpilot.telegram.commands.TelegramAuthorizationPolicy;
import com.jobpilot.telegram.commands.TelegramCommand;
import com.jobpilot.telegram.commands.TelegramCommandDispatcher;
import com.jobpilot.telegram.commands.TelegramCommandParser;
import com.jobpilot.telegram.commands.TelegramCommandResult;
import com.jobpilot.telegram.commands.TelegramMessageRenderer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelegramUpdateProcessorTest {
    private TelegramClient client;
    private TelegramCommandDispatcher dispatcher;
    private TelegramUpdateProcessor processor;

    @BeforeEach
    void setUp() {
        client = org.mockito.Mockito.mock(TelegramClient.class);
        dispatcher = org.mockito.Mockito.mock(TelegramCommandDispatcher.class);
        JobPilotProperties properties = properties();
        processor = new TelegramUpdateProcessor(client, new TelegramAuthorizationPolicy(properties),
                new TelegramCommandParser(properties), dispatcher, new TelegramMessageRenderer(), properties);
        when(dispatcher.dispatch(any(), any())).thenReturn(new TelegramCommandResult("done", "Saved"));
    }

    @Test
    void acceptsOnlyConfiguredChatAndUser() {
        processor.process(message(1, -100555, 777, "/save 42"));
        processor.process(message(2, -100999, 777, "/save 42"));
        processor.process(message(3, -100555, 999, "/save 42"));

        verify(dispatcher, org.mockito.Mockito.times(1)).dispatch(any(), any());
        verify(client, org.mockito.Mockito.times(1)).sendMessage(eq("-100555"), any(), any());
    }

    @Test
    void malformedAuthorizedCommandGetsSafeReplyWithoutDispatch() {
        processor.process(message(1, -100555, 777, "/save nope"));
        verify(dispatcher, never()).dispatch(any(), any());
        verify(client).sendMessage(eq("-100555"), any(), any());
    }

    @Test
    void validAndMalformedCallbacksAreAlwaysAnswered() {
        processor.process(callback(1, "app:applied:42", -100555, 777));
        processor.process(callback(2, "invalid free text", -100555, 777));

        ArgumentCaptor<TelegramCommand> command = ArgumentCaptor.forClass(TelegramCommand.class);
        verify(dispatcher).dispatch(command.capture(), any());
        verify(client).answerCallbackQuery("cb-1", "Saved");
        verify(client).answerCallbackQuery("cb-2", "Invalid action");
        org.assertj.core.api.Assertions.assertThat(command.getValue().kind())
                .isEqualTo(TelegramCommand.Kind.APPLIED);
    }

    @Test
    void replyFailureDoesNotFailOrRepeatCommittedMutation() {
        org.mockito.Mockito.doThrow(new TelegramTransportException(
                TelegramTransportException.Operation.SEND_MESSAGE))
                .when(client).sendMessage(any(), any(), any());

        assertThatCode(() -> processor.process(message(1, -100555, 777, "/save 42")))
                .doesNotThrowAnyException();

        verify(dispatcher, org.mockito.Mockito.times(1)).dispatch(any(), any());
    }

    @Test
    void unauthorizedCallbackIsNotDispatchedOrAcknowledgedButOffsetAdvances() {
        TelegramBotStateService state = org.mockito.Mockito.mock(TelegramBotStateService.class);
        when(state.load()).thenReturn(Optional.of(
                new TelegramBotStateService.StateSnapshot(0L, null, 0)));
        TelegramUpdate unauthorized = callback(1, "app:save:42", -100555, 999);
        when(client.getUpdates(1L, Duration.ofSeconds(25), 50))
                .thenReturn(List.of(unauthorized));

        TelegramUpdatePoller poller = new TelegramUpdatePoller(
                client, processor, state, properties());
        poller.pollOnce();

        verify(dispatcher, never()).dispatch(any(), any());
        verify(client, never()).answerCallbackQuery(any(), any());
        verify(client, never()).sendMessage(any(), any(), any());
        verify(state).markProcessed(1L);
        verify(state, never()).recordFailure(org.mockito.ArgumentMatchers.anyLong());
    }

    private TelegramUpdate message(long updateId, long chat, long user, String text) {
        return new TelegramUpdate(updateId,
                new TelegramMessage(5, new TelegramUser(user), new TelegramChat(chat), text), null);
    }

    private TelegramUpdate callback(long updateId, String data, long chat, long user) {
        TelegramMessage message = new TelegramMessage(5, null, new TelegramChat(chat), null);
        return new TelegramUpdate(updateId, null,
                new TelegramCallbackQuery("cb-" + updateId, new TelegramUser(user), message, data));
    }

    private JobPilotProperties properties() {
        return TestProperties.create(new JobPilotProperties.Telegram(
                "obviously-fake-token", "-100555", "JobPilotBot", true, "-100555", "777",
                Duration.ofSeconds(25), Duration.ofSeconds(2), 50, 3, true));
    }
}
