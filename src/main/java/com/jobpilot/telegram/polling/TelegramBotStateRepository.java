package com.jobpilot.telegram.polling;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramBotStateRepository extends JpaRepository<TelegramBotState, String> {
}
