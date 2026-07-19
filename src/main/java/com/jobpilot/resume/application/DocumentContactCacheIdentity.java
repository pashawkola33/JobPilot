package com.jobpilot.resume.application;

import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentContactBlock;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Produces an opaque keyed identity for private render-only contact data. */
@Component
public class DocumentContactCacheIdentity {
    private static final byte[] DOMAIN =
            "JobPilot\0document-contact-cache\0v1\0".getBytes(StandardCharsets.UTF_8);

    private final DocumentProperties properties;

    public DocumentContactCacheIdentity(DocumentProperties properties) {
        this.properties = properties;
    }

    public String fingerprint(DocumentContactBlock contact) {
        return fingerprint(contact, Optional.empty());
    }

    /** Owner scope is reserved for future cache partitioning; Stage 5 always passes empty. */
    public String fingerprint(DocumentContactBlock contact, Optional<String> ownerScope) {
        if (contact == null || ownerScope == null) {
            throw new IllegalArgumentException("Document contact-cache identity input is invalid.");
        }
        byte[] key = properties.decodedContactCacheHmacKey();
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update(DOMAIN);
            update(hmac, "owner", ownerScope.map(this::normalizeText).orElse(""));
            update(hmac, "email", normalizeEmail(contact.email()));
            update(hmac, "phone", normalizePhone(contact.phone()));
            List<String> links = contact.links() == null ? List.of() : contact.links();
            update(hmac, "link-count", Integer.toString(links.size()));
            for (int index = 0; index < links.size(); index++) {
                update(hmac, "link-" + index, normalizeLink(links.get(index)));
            }
            return HexFormat.of().formatHex(hmac.doFinal());
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("Document contact-cache identity is unavailable.");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private void update(Mac hmac, String label, String value) {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            hmac.update(ByteBuffer.allocate(Integer.BYTES).putInt(labelBytes.length).array());
            hmac.update(labelBytes);
            hmac.update(ByteBuffer.allocate(Integer.BYTES).putInt(valueBytes.length).array());
            hmac.update(valueBytes);
        } finally {
            Arrays.fill(valueBytes, (byte) 0);
        }
    }

    private String normalizeEmail(String value) {
        return normalizeText(value).toLowerCase(Locale.ROOT);
    }

    private String normalizePhone(String value) {
        return normalizeText(value).replaceAll("\\s+", " ");
    }

    private String normalizeLink(String value) {
        String normalized = normalizeText(value);
        try {
            URI uri = URI.create(normalized).normalize();
            int port = uri.getPort() == 443 ? -1 : uri.getPort();
            return new URI(uri.getScheme() == null ? null
                    : uri.getScheme().toLowerCase(Locale.ROOT), null,
                    uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT),
                    port, uri.getRawPath(), uri.getRawQuery(), uri.getRawFragment()).toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException invalid) {
            throw new IllegalArgumentException("Document contact-cache identity input is invalid.");
        }
    }

    private String normalizeText(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
    }
}
