package com.jobpilot.telegram.polling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobreview.application.JobQueue;
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
import com.jobpilot.telegram.review.TelegramCallbackData;
import com.jobpilot.telegram.review.TelegramCallbackData.Action;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelegramReviewCallbackTest {
    private static final long ALLOWED = 777L;
    private static final long INTRUDER = 999L;
    private static final long GROUP = -100555L;

    private TelegramClient client;
    private TelegramCommandDispatcher dispatcher;
    private TelegramUpdateProcessor processor;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        dispatcher = mock(TelegramCommandDispatcher.class);
        JobPilotProperties properties = TestProperties.create(
                new JobPilotProperties.Telegram("1234:obviously-fake", true,
                        List.of(Long.toString(ALLOWED)), true, true, 5, 500));
        processor = new TelegramUpdateProcessor(client,
                new TelegramAuthorizationPolicy(properties),
                new TelegramCommandParser(properties), dispatcher, new TelegramMessageRenderer());
        when(dispatcher.applyWorkflow(any(), anyLong()))
                .thenReturn(new TelegramCommandResult("done", "Saved"));
        when(dispatcher.dispatch(any(), any()))
                .thenReturn(new TelegramCommandResult("page", "Loaded"));
    }

    private TelegramUpdate callback(long chatId, long userId, String data) {
        TelegramMessage message = new TelegramMessage(5, new TelegramUser(userId),
                new TelegramChat(chatId), null);
        return new TelegramUpdate(1, null,
                new TelegramCallbackQuery("cb-1", new TelegramUser(userId), message, data));
    }

    private TelegramUpdate command(long chatId, long userId, String text) {
        return new TelegramUpdate(1,
                new TelegramMessage(5, new TelegramUser(userId), new TelegramChat(chatId), text),
                null);
    }

    @Test
    void appliesEveryWorkflowActionAndAcknowledgesTheCallback() {
        for (Action action : Action.values()) {
            processor.process(callback(ALLOWED, ALLOWED,
                    TelegramCallbackData.action(action, 42)));

            verify(dispatcher).applyWorkflow(action, 42L);
        }
        verify(client, times(Action.values().length)).answerCallbackQuery(eq("cb-1"), anyString());
    }

    @Test
    void paginatesThroughTheNextButton() {
        processor.process(callback(ALLOWED, ALLOWED,
                TelegramCallbackData.next(JobQueue.REVIEW, 2)));

        ArgumentCaptor<TelegramCommand> command = ArgumentCaptor.forClass(TelegramCommand.class);
        verify(dispatcher).dispatch(command.capture(), any());
        assertThat(command.getValue().kind()).isEqualTo(TelegramCommand.Kind.QUEUE);
        assertThat(command.getValue().queue()).isEqualTo(JobQueue.REVIEW);
        assertThat(command.getValue().page()).isEqualTo(2);
        verify(client).answerCallbackQuery("cb-1", "Loaded");
    }

    @Test
    void repeatingTheSameActionStaysIdempotentAtTheTransportBoundary() {
        String data = TelegramCallbackData.action(Action.SAVE, 42);

        processor.process(callback(ALLOWED, ALLOWED, data));
        processor.process(callback(ALLOWED, ALLOWED, data));

        // The workflow service decides idempotency; the processor simply replays the request.
        verify(dispatcher, times(2)).applyWorkflow(Action.SAVE, 42L);
        verify(client, times(2)).answerCallbackQuery(eq("cb-1"), anyString());
    }

    @Test
    void ignoresACallbackFromAnUnauthorizedChat() {
        processor.process(callback(INTRUDER, INTRUDER,
                TelegramCallbackData.action(Action.SAVE, 42)));

        verifyNoInteractions(dispatcher);
        verifyNoInteractions(client);
    }

    @Test
    void ignoresACallbackFromAGroupChatEvenWhenTheUserIsAllowed() {
        processor.process(callback(GROUP, ALLOWED, TelegramCallbackData.action(Action.SAVE, 42)));

        verifyNoInteractions(dispatcher);
        verifyNoInteractions(client);
    }

    @Test
    void ignoresACallbackWhereAnotherUserActsInsideAnAllowedChatId() {
        processor.process(callback(ALLOWED, INTRUDER, TelegramCallbackData.action(Action.SAVE, 42)));

        verifyNoInteractions(dispatcher);
        verifyNoInteractions(client);
    }

    @Test
    void acknowledgesMalformedCallbackDataWithoutTouchingTheWorkflow() {
        for (String hostile : List.of("jr:s:0", "jr:x:1", "wat", "", "jr:s:1;DROP TABLE jobs",
                "jn:m:1000", "jr:s:" + "9".repeat(40))) {
            processor.process(callback(ALLOWED, ALLOWED, hostile));
        }

        verify(dispatcher, never()).applyWorkflow(any(), anyLong());
        verify(client, times(7)).answerCallbackQuery("cb-1", "Invalid action");
    }

    @Test
    void stillAcknowledgesWhenTheReplyMessageCannotBeSent() {
        org.mockito.Mockito.doThrow(new TelegramTransportException(
                        TelegramTransportException.Operation.SEND_MESSAGE))
                .when(client).sendMessage(anyString(), anyString(), any());

        processor.process(callback(ALLOWED, ALLOWED, TelegramCallbackData.action(Action.SAVE, 42)));

        verify(client).answerCallbackQuery("cb-1", "Saved");
    }

    @Test
    void repliesIntoTheOriginatingChatOnly() {
        processor.process(callback(ALLOWED, ALLOWED, TelegramCallbackData.action(Action.SAVE, 42)));

        verify(client).sendMessage(eq(Long.toString(ALLOWED)), anyString(), any());
    }

    @Test
    void answersAuthorizedStartAndIgnoresUnauthorizedStart() {
        when(dispatcher.dispatch(any(), any()))
                .thenReturn(new TelegramCommandResult("<b>welcome</b>", "Done"));

        processor.process(command(ALLOWED, ALLOWED, "/start"));
        processor.process(command(INTRUDER, INTRUDER, "/start"));
        processor.process(command(GROUP, ALLOWED, "/start"));

        verify(client, times(1)).sendMessage(anyString(), anyString(), any());
        verify(client).sendMessage(eq(Long.toString(ALLOWED)), eq("<b>welcome</b>"), any());
    }

    @Test
    void sendsNoVacancyDataToAnUnauthorizedChat() {
        for (String text : List.of("/matches", "/review", "/saved", "/applied", "/stats",
                "/job 1", "/note 1 hi", "/reset 1")) {
            processor.process(command(INTRUDER, INTRUDER, text));
        }

        verifyNoInteractions(dispatcher);
        verifyNoInteractions(client);
    }
}
