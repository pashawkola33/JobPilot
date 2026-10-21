package com.jobpilot.notifications;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
class NotificationScheduleTest {
 @Test void quietHoursCrossMidnightAndNonHourZone(){
   assertThat(NotificationSchedule.quiet(Instant.parse("2026-10-20T20:30:00Z"),"Asia/Kathmandu","22:00","07:00")).isTrue();
   assertThat(NotificationSchedule.quiet(Instant.parse("2026-10-20T05:00:00Z"),"Asia/Kathmandu","22:00","07:00")).isFalse();
 }
 @Test void digestIsNineLocalAndNeverCatchesUpAnotherDate(){
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-20T08:59:00Z"),"UTC",null,null)).isFalse();
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-20T09:00:00Z"),"UTC",null,null)).isTrue();
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-21T00:00:00Z"),"UTC",null,null)).isFalse();
 }
 @Test void gapUsesFirstValidInstantAndOverlapUsesFirstOccurrence(){
   assertThat(NotificationSchedule.resolve(LocalDate.of(2026,3,8),LocalTime.of(2,30),ZoneId.of("America/New_York"))).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
   assertThat(NotificationSchedule.resolve(LocalDate.of(2026,11,1),LocalTime.of(1,30),ZoneId.of("America/New_York"))).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
   assertThat(NotificationSchedule.resolve(LocalDate.of(2026,3,29),LocalTime.of(3,30),ZoneId.of("Europe/Bucharest"))).isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
 }
 @Test void digestDefersUntilQuietEnd(){
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-20T09:00:00Z"),"UTC","08:00","10:30")).isFalse();
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-20T10:30:00Z"),"UTC","08:00","10:30")).isTrue();
 }

 @Test void digestQuietDeferralAcrossMidnightStillHasOneMorningWindow(){
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-21T06:59:00Z"),"UTC","08:00","07:00")).isFalse();
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-21T07:00:00Z"),"UTC","08:00","07:00")).isTrue();
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-21T07:59:00Z"),"UTC","08:00","07:00")).isTrue();
   assertThat(NotificationSchedule.digestDue(Instant.parse("2026-10-21T08:00:00Z"),"UTC","08:00","07:00")).isFalse();
 }
}
