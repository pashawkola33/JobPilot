package com.jobpilot.resume.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jobpilot.documents")
public record DocumentProperties(
        boolean enabled,
        Path storageRoot,
        long maxDocxBytes,
        long maxPdfBytes,
        String resumeTemplateVersion,
        String coverNoteTemplateVersion,
        String rendererVersion,
        int maxPreviewCharacters,
        Duration staleAfter,
        String contactCacheHmacKey,
        Contact contact) {

    private static final long MIN_ARTIFACT_BYTES = 1_024;
    private static final long MAX_ARTIFACT_BYTES = 20L * 1_024 * 1_024;

    public DocumentProperties {
        storageRoot = storageRoot == null ? Path.of("./data/documents") : storageRoot;
        resumeTemplateVersion = boundedToken(resumeTemplateVersion, "resume-v1");
        coverNoteTemplateVersion = boundedToken(coverNoteTemplateVersion, "cover-note-v1");
        rendererVersion = boundedToken(rendererVersion, "apache-v1");
        staleAfter = staleAfter == null ? Duration.ofMinutes(10) : staleAfter;
        contactCacheHmacKey = contactCacheHmacKey == null ? "" : contactCacheHmacKey.strip();
        contact = contact == null ? new Contact("", "", "", "", "") : contact;
        if (maxDocxBytes < MIN_ARTIFACT_BYTES || maxDocxBytes > MAX_ARTIFACT_BYTES
                || maxPdfBytes < MIN_ARTIFACT_BYTES || maxPdfBytes > MAX_ARTIFACT_BYTES
                || maxPreviewCharacters < 500 || maxPreviewCharacters > 20_000
                || staleAfter.compareTo(Duration.ofMinutes(2)) < 0
                || staleAfter.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Document limits are outside their safe bounds");
        }
        if (enabled) validateContactCacheHmacKey(contactCacheHmacKey);
    }

    public static DocumentProperties disabled() {
        return new DocumentProperties(false, Path.of("./data/documents"), 2_097_152,
                2_097_152, "resume-v1", "cover-note-v1", "apache-v1", 4_000,
                Duration.ofMinutes(10), "", new Contact("", "", "", "", ""));
    }

    public byte[] decodedContactCacheHmacKey() {
        return decodeContactCacheHmacKey(contactCacheHmacKey);
    }

    private static void validateContactCacheHmacKey(String encoded) {
        byte[] decoded = decodeContactCacheHmacKey(encoded);
        Arrays.fill(decoded, (byte) 0);
    }

    private static byte[] decodeContactCacheHmacKey(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 512) {
            throw new IllegalArgumentException(
                    "Enabled document generation requires a valid contact-cache HMAC key.");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length < 32 || decoded.length > 256) {
                Arrays.fill(decoded, (byte) 0);
                throw new IllegalArgumentException(
                        "The document contact-cache HMAC key does not meet strength requirements.");
            }
            return decoded;
        } catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null
                    && invalid.getMessage().startsWith("The document contact-cache")) {
                throw invalid;
            }
            throw new IllegalArgumentException(
                    "Enabled document generation requires a valid Base64 contact-cache HMAC key.");
        }
    }

    private static String boundedToken(String value, String defaultValue) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.strip();
        if (!normalized.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("Document version identifiers are invalid");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "DocumentProperties[enabled=" + enabled
                + ", storageRoot=<private>, contactCacheHmacKey=<redacted>, contact=<redacted>]";
    }

    public record Contact(String email, String phone, String githubUrl,
                          String linkedinUrl, String portfolioUrl) {
        public Contact {
            email = normalize(email);
            phone = normalize(phone);
            githubUrl = normalize(githubUrl);
            linkedinUrl = normalize(linkedinUrl);
            portfolioUrl = normalize(portfolioUrl);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
