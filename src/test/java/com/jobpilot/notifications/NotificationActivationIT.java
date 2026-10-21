package com.jobpilot.notifications;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.jobpilot.telegram.api.TelegramUpdate;
import com.jobpilot.telegram.api.TelegramUpdate.*;
import com.jobpilot.identity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

class NotificationActivationIT extends NotificationSettingsIT {
 @Autowired NotificationActivationService activation;
 TelegramUpdate event(String text,long user,long chat,String type,boolean bot,boolean forwarded,InstantEvidence evidence){return new TelegramUpdate(1,new TelegramMessage(1,new TelegramUser(user,bot),new TelegramChat(chat,type),text,evidence==InstantEvidence.PRESENT?java.time.Instant.now().getEpochSecond():null,forwarded),null);}
 enum InstantEvidence{PRESENT,MISSING}
 String payload()throws Exception{ok(a,write(update(0,true)));return ok(a,post(PATH+"/activation")).path("activationUrl").asText().split("start=")[1];}
 @Test void onlyPrivateSameUserCanConsumeSingleUseActivation()throws Exception{
  String command="/start "+payload();
  assertThat(activation.process(event(command,9003,9003,"private",false,false,InstantEvidence.PRESENT))).isTrue();
  assertThat(ok(a,get(PATH)).path("connectionStatus").asText()).isEqualTo("DISCONNECTED");
  assertThat(activation.process(event(command,9002,9002,"private",false,false,InstantEvidence.PRESENT))).isTrue();
  var connected=ok(a,get(PATH));assertThat(connected.path("connectionStatus").asText()).isEqualTo("CONNECTED");
  long generation=jdbc.queryForObject("select generation from notification_preferences where candidate_id=?",Long.class,candidate);
  activation.process(event(command,9002,9002,"private",false,false,InstantEvidence.PRESENT));
  assertThat(jdbc.queryForObject("select generation from notification_preferences where candidate_id=?",Long.class,candidate)).isEqualTo(generation);
  assertThat(ok(b,get(PATH)).path("connectionStatus").asText()).isEqualTo("DISCONNECTED");
 }
 @Test void groupForwardBotAndMissingEvidenceNeverActivate()throws Exception{String command="/start "+payload();for(TelegramUpdate e:java.util.List.of(event(command,9002,-50,"group",false,false,InstantEvidence.PRESENT),event(command,9002,9002,"private",false,true,InstantEvidence.PRESENT),event(command,9002,9002,"private",true,false,InstantEvidence.PRESENT),event(command,9002,9002,null,false,false,InstantEvidence.PRESENT),event(command,9002,9002,"private",false,false,InstantEvidence.MISSING),event(command,9002,9003,"private",false,false,InstantEvidence.PRESENT),new TelegramUpdate(1,new TelegramMessage(1,new TelegramUser(9002),new TelegramChat(9002,"private"),command,java.time.Instant.now().getEpochSecond(),false),null)))activation.process(e);assertThat(ok(a,get(PATH)).path("connectionStatus").asText()).isEqualTo("DISCONNECTED");}
 @Test void expiredOrRevokedChallengeCannotActivate()throws Exception{String command="/start "+payload();jdbc.update("update notification_activation_challenges set created_at=current_timestamp-interval '11 minutes',expires_at=current_timestamp-interval '1 minute'");activation.process(event(command,9002,9002,"private",false,false,InstantEvidence.PRESENT));assertThat(ok(a,get(PATH)).path("connectionStatus").asText()).isEqualTo("DISCONNECTED");}
 @Test void exactStopIsCandidateScopedAndNonExactDoesNothing()throws Exception{String command="/start "+payload();activation.process(event(command,9002,9002,"private",false,false,InstantEvidence.PRESENT));for(String text:java.util.List.of("/notifications_off now"," /notifications_off","/notifications_off@OtherBot"))assertThat(activation.process(event(text,9002,9002,"private",false,false,InstantEvidence.PRESENT))).isFalse();assertThat(ok(a,get(PATH)).path("preferences").path("enabled").asBoolean()).isTrue();activation.process(event("/notifications_off",9003,9003,"private",false,false,InstantEvidence.PRESENT));assertThat(ok(a,get(PATH)).path("preferences").path("enabled").asBoolean()).isTrue();activation.process(event("/notifications_off",9002,9002,"private",false,false,InstantEvidence.PRESENT));assertThat(ok(a,get(PATH)).path("preferences").path("enabled").asBoolean()).isFalse();}
 @Test void consentSurvivesBrowserExpiryButNotExplicitRevoke()throws Exception{String command="/start "+payload();var principal=identities.authenticate(a);activation.process(event(command,9002,9002,"private",false,false,InstantEvidence.PRESENT));jdbc.update("update auth_sessions set created_at=current_timestamp-interval '20 minutes',expires_at=current_timestamp-interval '1 minute' where token_hash=?",principal.sessionHash());assertThat(activation.consentValid(candidate)).isTrue();jdbc.update("update auth_sessions set revoked_at=current_timestamp where token_hash=?",principal.sessionHash());assertThat(activation.consentValid(candidate)).isFalse();}
 @Test void revokedConsentCannotBeRevivedByAnUnspentChallenge()throws Exception{String command="/start "+payload();identities.revoke(identities.authenticate(a));String newer=login(9002);activation.process(event(command,9002,9002,"private",false,false,InstantEvidence.PRESENT));assertThat(ok(newer,get(PATH)).path("preferences").path("enabled").asBoolean()).isFalse();assertThat(ok(newer,get(PATH)).path("connectionStatus").asText()).isEqualTo("DISCONNECTED");}

 @Test void ownerCommandsRemainOnTheLegacyPath(){assertThat(activation.process(event("/notifications_off",4242,4242,"private",false,false,InstantEvidence.PRESENT))).isFalse();assertThat(activation.process(event("/start notify_"+"a".repeat(43),4242,4242,"private",false,false,InstantEvidence.PRESENT))).isFalse();}

}
