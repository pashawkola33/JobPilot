package com.jobpilot.resume.validation;

import com.jobpilot.common.net.ProhibitedAddressClassifier;
import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentContactBlock;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DocumentContactPolicy {
    private static final String EMAIL_PATTERN =
            "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]{1,64}(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]{1,64})*"
                    + "@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+";

    private final DocumentProperties properties;

    public DocumentContactPolicy(DocumentProperties properties) {
        this.properties = properties;
    }

    public DocumentContactBlock requireValidContact() {
        DocumentProperties.Contact contact = properties.contact();
        if (!validEmail(contact.email())) {
            throw new DocumentConfigurationException(
                    "Document generation requires a syntactically valid contact email.");
        }
        if (!validPhone(contact.phone())) {
            throw new DocumentConfigurationException("Document contact phone is invalid.");
        }
        List<String> links = new ArrayList<>();
        addLink(links, contact.githubUrl());
        addLink(links, contact.linkedinUrl());
        addLink(links, contact.portfolioUrl());
        return new DocumentContactBlock(contact.email(), contact.phone(), links);
    }

    boolean validEmail(String value) {
        return value != null && value.length() >= 6 && value.length() <= 254
                && !value.contains("..") && value.matches(EMAIL_PATTERN);
    }

    boolean validPhone(String value) {
        if (value == null || value.isBlank()) return true;
        if (value.length() > 40 || !value.matches("[+0-9(). -]+")) return false;
        long digits = value.chars().filter(Character::isDigit).count();
        return digits >= 7 && digits <= 20;
    }

    private void addLink(List<String> links, String value) {
        if (value == null || value.isBlank()) return;
        if (!safeHttpsLink(value)) {
            throw new DocumentConfigurationException("A document contact link is unsafe.");
        }
        links.add(value);
    }

    boolean safeHttpsLink(String value) {
        if (value.length() > 500 || value.chars().anyMatch(Character::isISOControl)) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (uri.isOpaque() || !"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")
                    || normalizedHost.endsWith(".local") || normalizedHost.endsWith(".internal")
                    || !normalizedHost.contains(".")) {
                return false;
            }
            if (looksLikeIpLiteral(normalizedHost)) {
                InetAddress parsed = InetAddress.getByName(normalizedHost);
                if (ProhibitedAddressClassifier.isProhibited(parsed)) return false;
            }
            String path = uri.getRawPath();
            return path == null || !path.contains("\\") && !path.contains("..;");
        } catch (RuntimeException | java.net.UnknownHostException invalid) {
            return false;
        }
    }

    private boolean looksLikeIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }
}
