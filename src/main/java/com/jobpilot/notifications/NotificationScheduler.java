package com.jobpilot.notifications;
import org.springframework.stereotype.Component;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.core.env.Environment;
@Component
public class NotificationScheduler {
 private final NotificationOutboxService outbox;private final NotificationDeliveryWorker worker;private final Environment env;
 public NotificationScheduler(NotificationOutboxService outbox,NotificationDeliveryWorker worker,Environment env){this.outbox=outbox;this.worker=worker;this.env=env;}
 @Scheduled(fixedDelayString="${jobpilot.notifications.tick-ms:1000}") public void tick(){if(!env.getProperty("jobpilot.scheduled-tasks-enabled",Boolean.class,true))return;for(int n=0;n<25;n++)if(!outbox.admitOne())break;worker.deliverOne();}
}
