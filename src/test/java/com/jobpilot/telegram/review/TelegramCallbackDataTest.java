package com.jobpilot.telegram.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobreview.application.JobQueue;
import com.jobpilot.telegram.review.TelegramCallbackData.Action;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramCallbackDataTest {
    @Test
    void roundTripsEveryActionWithinTheBotApiPayloadLimit() {
        for (Action action : Action.values()) {
            String data = TelegramCallbackData.action(action, 9_223_372_036_854_775_806L);

            assertThat(data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .as(data).isLessThanOrEqualTo(TelegramCallbackData.MAX_LENGTH);
            assertThat(TelegramCallbackData.parseAction(data))
                    .isEqualTo(new TelegramCallbackData.ActionCommand(action, 9_223_372_036_854_775_806L));
        }
    }

    @Test
    void roundTripsEveryQueueForPagination() {
        for (JobQueue queue : JobQueue.values()) {
            String data = TelegramCallbackData.next(queue, 7);

            assertThat(data.length()).isLessThanOrEqualTo(TelegramCallbackData.MAX_LENGTH);
            assertThat(TelegramCallbackData.parseNext(data))
                    .isEqualTo(new TelegramCallbackData.NextCommand(queue, 7));
        }
    }

    @Test
    void carriesNothingButAnActionLetterAndOneInteger() {
        String data = TelegramCallbackData.action(Action.SAVE, 42);

        assertThat(data).isEqualTo("jr:s:42");
        assertThat(TelegramCallbackData.next(JobQueue.MATCHES, 3)).isEqualTo("jn:m:3");
    }

    @Test
    void rejectsMalformedOrOversizedPayloads() {
        List<String> hostile = List.of("", " ", "jr:s:", "jr:x:1", "jr:s:0", "jr:s:-1",
                "jr:s:1x", "jr:s:1 ", " jr:s:1", "jr:s:1\n", "app:save:1", "jr:s:1;DROP TABLE jobs",
                "jr:s:99999999999999999999", "jn:z:1", "jn:m:", "jn:m:1000", "jn:m:-1",
                "jr:s:" + "1".repeat(80));

        for (String data : hostile) {
            assertThat(TelegramCallbackData.parseAction(data)).as("action " + data).isNull();
            assertThat(TelegramCallbackData.parseNext(data)).as("next " + data).isNull();
        }
        assertThat(TelegramCallbackData.parseAction(null)).isNull();
        assertThat(TelegramCallbackData.parseNext(null)).isNull();
    }

    @Test
    void doesNotCrossParseActionAndPaginationPayloads() {
        assertThat(TelegramCallbackData.parseNext(TelegramCallbackData.action(Action.SAVE, 5)))
                .isNull();
        assertThat(TelegramCallbackData.parseAction(TelegramCallbackData.next(JobQueue.SAVED, 5)))
                .isNull();
    }
}
