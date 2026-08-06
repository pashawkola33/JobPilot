package com.jobpilot.miniapp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.config.JobPilotProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * Validates the raw {@code Telegram.WebApp.initData} query string per
 * <a href="https://core.telegram.org/bots/webapps">Validating data received via the Mini App</a>.
 *
 * <p>The result is either a Telegram user id or a failure reason. Nothing here logs, and no
 * method returns the raw initData, the bot token, the hash, or the decoded user JSON, so a
 * caller cannot accidentally propagate them into a response or a log line.
 */
@Component
public class TelegramInitDataValidator {
    /** Longest initData Telegram realistically emits; anything larger is rejected unparsed. */
    static final int MAX_LENGTH = 4096;
    private static final String HMAC = "HmacSHA256";
    private static final byte[] WEB_APP_DATA = "WebAppData".getBytes(StandardCharsets.UTF_8);

    /**
     * Telegram allows a small amount of clock skew between its servers and ours. Without
     * this, a correctly issued token can look future-dated and be rejected.
     */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    public enum Failure {
        MALFORMED,
        INVALID_HASH,
        EXPIRED,
        /** auth_date is further in the future than clock skew explains. */
        NOT_YET_VALID
    }

    /** Exactly one of {@code userId} and {@code failure} is present. */
    public record Result(Long userId, Failure failure) {
        public boolean valid() {
            return userId != null;
        }

        static Result of(long userId) {
            return new Result(userId, null);
        }

        static Result failed(Failure failure) {
            return new Result(null, failure);
        }
    }

    private final JobPilotProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TelegramInitDataValidator(JobPilotProperties properties, ObjectMapper objectMapper,
                                     Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Result validate(String initData) {
        if (initData == null || initData.isBlank() || initData.length() > MAX_LENGTH) {
            return Result.failed(Failure.MALFORMED);
        }

        String hash = null;
        String authDate = null;
        String user = null;
        // The data-check-string omits only `hash`. `signature` belongs to the separate
        // Ed25519 third-party scheme but is still covered by the HMAC, so it stays in.
        List<String> checkPairs = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (String pair : initData.split("&", -1)) {
            int separator = pair.indexOf('=');
            // A field with no '=' is not a well-formed pair; an empty key is unusable.
            if (separator <= 0) return Result.failed(Failure.MALFORMED);
            String key = decode(pair.substring(0, separator));
            String value = decode(pair.substring(separator + 1));
            if (key == null || value == null) return Result.failed(Failure.MALFORMED);
            // A duplicated key makes the signed payload ambiguous: reject rather than guess.
            if (!seenKeys.add(key)) return Result.failed(Failure.MALFORMED);

            switch (key) {
                case "hash" -> hash = value;
                case "auth_date" -> {
                    authDate = value;
                    checkPairs.add(key + "=" + value);
                }
                case "user" -> {
                    user = value;
                    checkPairs.add(key + "=" + value);
                }
                default -> checkPairs.add(key + "=" + value);
            }
        }

        if (hash == null || authDate == null || user == null) return Result.failed(Failure.MALFORMED);

        checkPairs.sort(null);
        String dataCheckString = String.join("\n", checkPairs);
        if (!hashMatches(dataCheckString, hash)) return Result.failed(Failure.INVALID_HASH);

        // Freshness is only meaningful once the payload is proven authentic.
        Failure freshness = checkFreshness(authDate);
        if (freshness != null) return Result.failed(freshness);

        Long userId = userId(user);
        return userId == null ? Result.failed(Failure.MALFORMED) : Result.of(userId);
    }

    private boolean hashMatches(String dataCheckString, String receivedHash) {
        byte[] expected;
        try {
            byte[] secretKey = hmac(WEB_APP_DATA,
                    properties.telegram().botToken().getBytes(StandardCharsets.UTF_8));
            expected = hmac(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            // HmacSHA256 is mandatory on every JRE; treat an absence as a failed check.
            return false;
        }
        byte[] received = decodeHex(receivedHash);
        // MessageDigest.isEqual is the JDK's constant-time comparison. A length mismatch
        // still returns early, which is why the length is not secret: only the bytes are.
        return received != null && MessageDigest.isEqual(expected, received);
    }

    private Failure checkFreshness(String authDate) {
        long seconds;
        try {
            seconds = Long.parseLong(authDate);
        } catch (NumberFormatException malformed) {
            return Failure.MALFORMED;
        }
        if (seconds <= 0) return Failure.MALFORMED;

        Instant issued;
        try {
            issued = Instant.ofEpochSecond(seconds);
        } catch (RuntimeException outOfRange) {
            return Failure.MALFORMED;
        }
        Instant now = clock.instant();
        if (issued.isAfter(now.plus(MAX_CLOCK_SKEW))) return Failure.NOT_YET_VALID;
        return issued.isBefore(now.minus(properties.miniApp().maxAuthAge())) ? Failure.EXPIRED : null;
    }

    /** Only the numeric id is read; the rest of the user object is never surfaced. */
    private Long userId(String userJson) {
        try {
            JsonNode node = objectMapper.readTree(userJson);
            if (node == null || !node.isObject()) return null;
            JsonNode id = node.get("id");
            // A string id, a float, or a missing id is not a Telegram user object.
            if (id == null || !id.isIntegralNumber()) return null;
            long value = id.longValue();
            return value > 0 ? value : null;
        } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException malformed) {
            return null;
        }
    }

    private static byte[] hmac(byte[] key, byte[] message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(key, HMAC));
        return mac.doFinal(message);
    }

    /** Returns null for anything that is not exactly 64 lowercase-or-uppercase hex digits. */
    private static byte[] decodeHex(String value) {
        if (value == null || value.length() != 64) return null;
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            bytes[i] = (byte) (high << 4 | low);
        }
        return bytes;
    }

    /** Percent-decoding that rejects malformed escapes instead of substituting them. */
    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
