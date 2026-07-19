package com.jobpilot.telegram.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.manualurl.application.ManualJobUrlService;
import com.jobpilot.manualurl.domain.ManualJobStatus;
import com.jobpilot.manualurl.domain.ManualJobSubmissionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramCommandDispatcherTest {
    @Test
    void addDelegatesDirectlyToTheExistingManualJobUrlService() {
        ApplicationTrackerService tracker = org.mockito.Mockito.mock(ApplicationTrackerService.class);
        ManualJobUrlService manual = org.mockito.Mockito.mock(ManualJobUrlService.class);
        TelegramMessageRenderer renderer = new TelegramMessageRenderer();
        var dispatcher = new TelegramCommandDispatcher(tracker, manual, renderer);
        String publicUrl = "https://jobs.example.test/vacancies/42";
        when(manual.submit(publicUrl)).thenReturn(new ManualJobSubmissionResult(
                ManualJobStatus.CREATED, 42L, publicUrl, 88,
                List.of("Java"), List.of(), "Vacancy created."));

        TelegramCommandResult result = dispatcher.dispatch(new TelegramCommand(
                TelegramCommand.Kind.ADD, null, publicUrl, null, null, null),
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);

        verify(manual).submit(publicUrl);
        assertThat(result.html()).contains("CREATED", "Job ID: 42", "88/100");
    }
}
