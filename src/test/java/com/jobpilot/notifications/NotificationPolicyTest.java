package com.jobpilot.notifications;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class NotificationPolicyTest {
 @Test void disabledDefaultsAreUsefulAndExplicit(){var p=NotificationPreferences.defaults();assertThat(p.enabled()).isFalse();assertThat(p.paused()).isFalse();assertThat(p.immediate()).isTrue();assertThat(p.digest()).isFalse();assertThat(p.minimumScore()).isEqualTo(85);assertThat(p.timezone()).isEqualTo("UTC");assertThat(p.quietStart()).isNull();}
 @Test void supportedIanaZonesAndOvernightQuietWindowRemainValid(){for(String zone:java.util.List.of("Europe/Bucharest","America/New_York","Asia/Kathmandu","UTC"))assertThat(new NotificationPreferences(true,false,true,true,0,zone,"23:00","07:00").timezone()).isEqualTo(zone);}
 @Test void lockHelperRequiresTransaction(){assertThatThrownBy(()->NotificationLocks.bound(new org.springframework.jdbc.core.JdbcTemplate())).isInstanceOf(IllegalStateException.class);}
 @Test void recoveryRejectsActivationBeforeAnyDatabaseAccess(){
  var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("recovery");
  var settings=new NotificationSettingsService(new org.springframework.jdbc.core.JdbcTemplate(),null,null,new NotificationProperties(true),env,new com.fasterxml.jackson.databind.ObjectMapper(),java.time.Clock.systemUTC());
  var service=new NotificationActivationService(new org.springframework.jdbc.core.JdbcTemplate(),null,settings,null,null,java.time.Clock.systemUTC());
  assertThatThrownBy(()->service.challenge(null)).isInstanceOf(com.jobpilot.identity.IdentityException.class).hasMessage("RECOVERY_MODE");
  assertThat(service.process(new com.jobpilot.telegram.api.TelegramUpdate(1,new com.jobpilot.telegram.api.TelegramUpdate.TelegramMessage(1,null,null,"/notifications_off"),null))).isTrue();
 }
}

