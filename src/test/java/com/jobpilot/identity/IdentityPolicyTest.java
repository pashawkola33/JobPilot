package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.candidate.CandidateProfileTestData;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import com.jobpilot.miniapp.auth.TelegramInitDataValidator;
import com.jobpilot.support.TestProperties;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityPolicyTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"),ZoneOffset.UTC);
    private IdentityProperties settings(boolean enabled,boolean registration,Long owner,List<Long> beta) {
        return new IdentityProperties(enabled,registration,owner,beta,null,null);
    }
    @Test void disabledFeatureAndClosedRegistrationCannotAdmitNewUsers() {
        var disabled=settings(false,false,null,List.of());
        var service=new TelegramIdentityService(new JdbcTemplate(),disabled,new TelegramInitDataValidator(TestProperties.create(),new ObjectMapper(),clock),TestProperties.create(),clock);
        assertThatThrownBy(()->service.exchange("anything")).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.status()).isEqualTo(503));
        assertThatThrownBy(()->service.authenticate("a".repeat(43))).isInstanceOf(IdentityException.class);
        assertThat(settings(true,false,4242L,List.of(9002L)).admits(9002)).isFalse();
        assertThat(settings(true,false,4242L,List.of(9002L)).admits(4242)).isTrue();
    }
    @Test void missingOwnerAndExcessiveSessionLifetimeFailConfiguration() {
        assertThatThrownBy(()->settings(true,true,null,List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new IdentityProperties(true,true,4242L,List.of(),Duration.ofHours(1),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->settings(true,true,-1L,List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyWorkflowWithoutExplicitOwnerFailsClosedEvenWithRegistrationDisabled() {
        var properties=TestProperties.create(new com.jobpilot.config.JobPilotProperties.Telegram(TelegramInitDataFixture.BOT_TOKEN,""),new com.jobpilot.config.JobPilotProperties.MiniApp(true,List.of("4242"),Duration.ofHours(1)));
        assertThatThrownBy(()->new IdentityConfigurationGuard(settings(false,false,null,List.of()),properties,CandidateProfileTestData.validProfile(1),new JdbcTemplate()).afterSingletonsInstantiated())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new IdentityConfigurationGuard(settings(false,false,9002L,List.of()),properties,CandidateProfileTestData.validProfile(1),new JdbcTemplate()).afterSingletonsInstantiated())
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidLaunchFloodIsBoundedWithoutDatabaseWrites() {
        var properties=TestProperties.create(new com.jobpilot.config.JobPilotProperties.Telegram(TelegramInitDataFixture.BOT_TOKEN,""));
        var service=new TelegramIdentityService(new JdbcTemplate(),settings(true,true,4242L,List.of(9002L)),new TelegramInitDataValidator(properties,new ObjectMapper(),clock),properties,clock);
        for(int i=0;i<120;i++) assertThatThrownBy(()->service.exchange("invalid")).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.status()).isEqualTo(401));
        assertThatThrownBy(()->service.exchange("invalid")).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.status()).isEqualTo(429));
    }
    @Test void outOfRangeTelegramIntegerCannotWrapIntoAnAllowedIdentity() {
        var properties=TestProperties.create(new com.jobpilot.config.JobPilotProperties.Telegram(TelegramInitDataFixture.BOT_TOKEN,""));
        String data=TelegramInitDataFixture.launch(4242,clock.instant().getEpochSecond()).with("user","{\"id\":18446744073709555858}").signed();
        assertThat(new TelegramInitDataValidator(properties,new ObjectMapper(),clock).validate(data).valid()).isFalse();
    }
    @Test void invalidOwnerStopsContextBeforeScheduledWorkCanStart() {
        var properties=TestProperties.create(new com.jobpilot.config.JobPilotProperties.Telegram(TelegramInitDataFixture.BOT_TOKEN,""),new com.jobpilot.config.JobPilotProperties.MiniApp(true,List.of("4242"),Duration.ofHours(1)));
        var refreshed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(IdentityConfigurationGuard.class,()->new IdentityConfigurationGuard(settings(false,false,null,List.of()),properties,CandidateProfileTestData.validProfile(1),new JdbcTemplate()));
            context.addApplicationListener((org.springframework.context.ApplicationListener<org.springframework.context.event.ContextRefreshedEvent>)event->refreshed.set(true));
            assertThatThrownBy(context::refresh).isInstanceOf(IllegalArgumentException.class);
            assertThat(refreshed).isFalse();
        }
    }
}
