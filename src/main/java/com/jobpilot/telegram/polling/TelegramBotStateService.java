package com.jobpilot.telegram.polling;

import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TelegramBotStateService {
    private final TelegramBotStateRepository repository;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public TelegramBotStateService(TelegramBotStateRepository repository, Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Optional<StateSnapshot> load() {
        return transactions.execute(status -> repository.findById(TelegramBotState.LONG_POLLING_KEY)
                .map(this::snapshot));
    }

    public void initialize(Long lastProcessedUpdateId) {
        transactions.executeWithoutResult(status -> {
            if (!repository.existsById(TelegramBotState.LONG_POLLING_KEY)) {
                repository.save(new TelegramBotState(lastProcessedUpdateId, clock.instant()));
            }
        });
    }

    public void markProcessed(long updateId) {
        transactions.executeWithoutResult(status -> {
            TelegramBotState state = repository.findById(TelegramBotState.LONG_POLLING_KEY)
                    .orElseGet(() -> new TelegramBotState(null, clock.instant()));
            state.markProcessed(updateId, clock.instant());
            repository.save(state);
        });
    }

    public int recordFailure(long updateId) {
        return transactions.execute(status -> {
            TelegramBotState state = repository.findById(TelegramBotState.LONG_POLLING_KEY)
                    .orElseGet(() -> new TelegramBotState(null, clock.instant()));
            int attempts = state.recordFailure(updateId, clock.instant());
            repository.save(state);
            return attempts;
        });
    }

    private StateSnapshot snapshot(TelegramBotState state) {
        return new StateSnapshot(state.getLastProcessedUpdateId(), state.getFailedUpdateId(),
                state.getFailedAttempts());
    }

    public record StateSnapshot(Long lastProcessedUpdateId, Long failedUpdateId, int failedAttempts) {
    }
}
