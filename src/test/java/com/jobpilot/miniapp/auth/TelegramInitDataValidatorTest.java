package com.jobpilot.miniapp.auth;

import static com.jobpilot.miniapp.auth.TelegramInitDataFixture.BOT_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.miniapp.auth.TelegramInitDataValidator.Failure;
import com.jobpilot.support.TestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelegramInitDataValidatorTest {
    private static final long USER_ID = 4242L;
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    private static final long FRESH = NOW.minus(Duration.ofMinutes(5)).getEpochSecond();

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TelegramInitDataValidator validator = validatorWith(BOT_TOKEN);

    private TelegramInitDataValidator validatorWith(String botToken) {
        JobPilotProperties properties = TestProperties.create(
                new JobPilotProperties.Telegram(botToken, ""));
        return new TelegramInitDataValidator(properties, new ObjectMapper(), clock);
    }

    @Test
    void acceptsCorrectlySignedInitData() {
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH).signed();

        TelegramInitDataValidator.Result result = validator.validate(initData);

        assertThat(result.valid()).isTrue();
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.failure()).isNull();
    }

    @Test
    void rejectsDataSignedWithADifferentBotToken() {
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH)
                .signedWith("999999:some-other-bot-token");

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.INVALID_HASH);
    }

    @Test
    void rejectsATamperedUserValue() {
        // The attacker swaps in their own id but cannot recompute the hash.
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH)
                .signedThenTampered("user", "{\"id\":99,\"first_name\":\"Mallory\"}");

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.INVALID_HASH);
    }

    @Test
    void rejectsATamperedAuthDate() {
        String stale = NOW.minus(Duration.ofDays(2)).getEpochSecond() + "";
        String initData = TelegramInitDataFixture.launch(USER_ID, Long.parseLong(stale))
                .signedThenTampered("auth_date", Long.toString(FRESH));

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.INVALID_HASH);
    }

    @Test
    void rejectsAnInvalidHash() {
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH).signed()
                .replaceAll("&hash=.*$", "&hash=" + "0".repeat(64));

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.INVALID_HASH);
    }

    @Test
    void rejectsAMissingHash() {
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH).signed()
                .replaceAll("&hash=.*$", "");

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.MALFORMED);
    }

    @Test
    void rejectsMalformedPercentEncoding() {
        String initData = "auth_date=" + FRESH + "&user=%ZZ&hash=" + "a".repeat(64);

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "auth_date", "hash"})
    void rejectsDuplicatedCriticalFields(String duplicated) {
        // An ambiguous payload must never be resolved by picking one occurrence.
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH).signed()
                + "&" + duplicated + "=injected";

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.MALFORMED);
    }

    @Test
    void rejectsAMissingUser() {
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH).without("user").signed();

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json at all",
            "[]",
            "{}",
            "{\"id\":\"4242\"}",
            "{\"id\":4242.5}",
            "{\"id\":0}",
            "{\"id\":-4242}",
    })
    void rejectsAUserObjectWithoutAUsablePositiveIntegerId(String userJson) {
        // Correctly signed, so only the user object itself can be the reason it fails.
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH)
                .with("user", userJson).signed();

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.MALFORMED);
    }

    @Test
    void rejectsAnExpiredAuthDate() {
        long stale = NOW.minus(Duration.ofHours(2)).getEpochSecond();
        String initData = TelegramInitDataFixture.launch(USER_ID, stale).signed();

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.EXPIRED);
    }

    @Test
    void acceptsAnAuthDateAtTheEdgeOfTheConfiguredWindow() {
        long edge = NOW.minus(JobPilotProperties.MiniApp.DEFAULT_MAX_AUTH_AGE)
                .plusSeconds(1).getEpochSecond();

        assertThat(validator.validate(
                TelegramInitDataFixture.launch(USER_ID, edge).signed()).valid()).isTrue();
    }

    @Test
    void rejectsAnImplausiblyFutureDatedAuthDate() {
        long ahead = NOW.plus(Duration.ofHours(1)).getEpochSecond();
        String initData = TelegramInitDataFixture.launch(USER_ID, ahead).signed();

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.NOT_YET_VALID);
    }

    @Test
    void toleratesSmallForwardClockSkew() {
        // Telegram's clock running a minute ahead of ours is not an attack.
        long slightlyAhead = NOW.plus(Duration.ofMinutes(1)).getEpochSecond();

        assertThat(validator.validate(
                TelegramInitDataFixture.launch(USER_ID, slightlyAhead).signed()).valid()).isTrue();
    }

    @Test
    void acceptsUnicodeAndPercentEncodedValues() {
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH)
                .with("user", "{\"id\":" + USER_ID
                        + ",\"first_name\":\"Ана Мария\",\"last_name\":\"O'Brien & Co\"}")
                .with("start_param", "a b+c%d=e")
                .signed();

        TelegramInitDataValidator.Result result = validator.validate(initData);

        assertThat(result.valid()).isTrue();
        assertThat(result.userId()).isEqualTo(USER_ID);
    }

    @Test
    void includesUnknownFieldsInTheSignedPayload() {
        // Telegram signs every field except hash, so an unknown field must still be
        // covered: dropping it would let an attacker append arbitrary signed-looking data.
        String initData = TelegramInitDataFixture.launch(USER_ID, FRESH)
                .with("signature", "third-party-ed25519-value")
                .signedThenTampered("signature", "swapped");

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.INVALID_HASH);
    }

    @Test
    void rejectsHashesThatAreNotExactlySixtyFourHexDigits() {
        String base = TelegramInitDataFixture.launch(USER_ID, FRESH).signed()
                .replaceAll("&hash=.*$", "");

        assertThat(validator.validate(base + "&hash=abc").failure()).isEqualTo(Failure.INVALID_HASH);
        assertThat(validator.validate(base + "&hash=" + "z".repeat(64)).failure())
                .isEqualTo(Failure.INVALID_HASH);
    }

    @Test
    void rejectsOversizedInitDataWithoutParsingIt() {
        String initData = "user=" + "x".repeat(TelegramInitDataValidator.MAX_LENGTH);

        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "nopairs", "=novalue"})
    void rejectsStructurallyUnusableInput(String initData) {
        assertThat(validator.validate(initData).failure()).isEqualTo(Failure.MALFORMED);
    }

    @Test
    void rejectsNullInitData() {
        assertThat(validator.validate(null).failure()).isEqualTo(Failure.MALFORMED);
    }

    /**
     * Every wrong hash of the correct length must be rejected regardless of where it first
     * differs, which is the observable half of using a constant-time comparison. The other
     * half — that the comparison does not return early — is a property of
     * MessageDigest.isEqual, asserted here so replacing it with String.equals fails.
     */
    @Test
    void comparesHashesRegardlessOfWhereTheFirstDifferenceFalls() {
        String correct = TelegramInitDataFixture.launch(USER_ID, FRESH).hash(BOT_TOKEN);
        String base = TelegramInitDataFixture.launch(USER_ID, FRESH).signed()
                .replaceAll("&hash=.*$", "");

        String differsFirst = flip(correct, 0);
        String differsLast = flip(correct, correct.length() - 1);

        assertThat(validator.validate(base + "&hash=" + differsFirst).failure())
                .isEqualTo(Failure.INVALID_HASH);
        assertThat(validator.validate(base + "&hash=" + differsLast).failure())
                .isEqualTo(Failure.INVALID_HASH);
        assertThat(validator.validate(base + "&hash=" + correct).valid()).isTrue();
    }

    private static String flip(String hash, int index) {
        char original = hash.charAt(index);
        char replacement = original == '0' ? '1' : '0';
        return hash.substring(0, index) + replacement + hash.substring(index + 1);
    }
}
