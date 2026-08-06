package com.jobpilot.miniapp.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs initData exactly the way Telegram does, so validator tests never depend on a
 * hand-copied fixture that could drift from the algorithm under test.
 */
public final class TelegramInitDataFixture {
    public static final String BOT_TOKEN = "123456:test-bot-token-not-a-real-secret";

    private final Map<String, String> fields = new LinkedHashMap<>();

    private TelegramInitDataFixture() {
    }

    /** A well-formed launch payload for the given user at the given unix second. */
    public static TelegramInitDataFixture launch(long userId, long authDateEpochSecond) {
        TelegramInitDataFixture fixture = new TelegramInitDataFixture();
        fixture.fields.put("auth_date", Long.toString(authDateEpochSecond));
        fixture.fields.put("query_id", "AAF_test_query");
        fixture.fields.put("user", "{\"id\":" + userId
                + ",\"first_name\":\"Test\",\"language_code\":\"en\"}");
        return fixture;
    }

    public TelegramInitDataFixture with(String key, String value) {
        fields.put(key, value);
        return this;
    }

    public TelegramInitDataFixture without(String key) {
        fields.remove(key);
        return this;
    }

    /** Percent-encoded query string carrying a correct hash for {@code token}. */
    public String signedWith(String token) {
        return encode(fields) + "&hash=" + hash(token);
    }

    public String signed() {
        return signedWith(BOT_TOKEN);
    }

    /**
     * Signs, then replaces one field's value without re-signing — the shape of a tampered
     * payload that a naive implementation would accept.
     */
    public String signedThenTampered(String key, String tamperedValue) {
        String hash = hash(BOT_TOKEN);
        Map<String, String> tampered = new LinkedHashMap<>(fields);
        tampered.put(key, tamperedValue);
        return encode(tampered) + "&hash=" + hash;
    }

    /** The data-check-string Telegram signs: every field except hash, sorted, LF-joined. */
    private String dataCheckString() {
        List<String> pairs = new ArrayList<>();
        fields.forEach((key, value) -> pairs.add(key + "=" + value));
        pairs.sort(null);
        return String.join("\n", pairs);
    }

    public String hash(String token) {
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        return hex(hmac(secret, dataCheckString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(Map<String, String> fields) {
        List<String> parts = new ArrayList<>();
        fields.forEach((key, value) -> parts.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return String.join("&", parts);
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value));
        return text.toString();
    }
}
