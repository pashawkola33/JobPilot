package com.jobpilot.config;

import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jobpilot")
public record JobPilotProperties(
        Telegram telegram,
        Sources sources,
        Candidate candidate,
        Http http,
        ManualUrl manualUrl,
        Llm llm,
        Scheduling scheduling,
        List<String> searchTerms,
        List<String> locations) {

    public JobPilotProperties {
        telegram = telegram == null ? new Telegram("", "") : telegram;
        llm = llm == null ? Llm.disabled() : llm;
    }

    public record Telegram(
            String botToken,
            String channelId,
            String botUsername,
            boolean commandsEnabled,
            String allowedChatId,
            String allowedUserId,
            Duration pollTimeout,
            Duration pollDelay,
            int pollLimit,
            int maxUpdateFailures,
            boolean discardPendingOnFirstStart) {
        public Telegram {
            botUsername = normalizeBotUsername(botUsername);
            pollTimeout = pollTimeout == null ? Duration.ofSeconds(25) : pollTimeout;
            pollDelay = pollDelay == null ? Duration.ofSeconds(2) : pollDelay;
            if (pollTimeout.isNegative() || pollTimeout.compareTo(Duration.ofSeconds(50)) > 0
                    || pollDelay.isNegative() || pollDelay.compareTo(Duration.ofMinutes(1)) > 0
                    || pollLimit < 1 || pollLimit > 100
                    || maxUpdateFailures < 1 || maxUpdateFailures > 20) {
                throw new IllegalArgumentException("Telegram polling limits are outside their safe bounds");
            }
            if (commandsEnabled && (blank(botToken) || !validBotUsername(botUsername)
                    || !validChatId(allowedChatId)
                    || !validUserId(allowedUserId))) {
                throw new IllegalArgumentException(
                        "Telegram commands require a bot token, bot username, and explicit chat and user authorization");
            }
        }

        public Telegram(String botToken, String channelId) {
            this(botToken, channelId, "", false, "", "", Duration.ofSeconds(25),
                    Duration.ofSeconds(2), 50, 3, true);
        }

        public boolean enabled() {
            return !blank(botToken);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }

        private static String normalizeBotUsername(String value) {
            if (value == null) return "";
            String normalized = value.strip();
            return normalized.startsWith("@") ? normalized.substring(1) : normalized;
        }

        private static boolean validBotUsername(String value) {
            return value != null && value.matches("(?i)[a-z0-9_]{5,32}")
                    && value.toLowerCase(java.util.Locale.ROOT).endsWith("bot");
        }

        private static boolean validChatId(String value) {
            return validLong(value, "-?[1-9]\\d{0,18}");
        }

        private static boolean validUserId(String value) {
            return validLong(value, "[1-9]\\d{0,18}");
        }

        private static boolean validLong(String value, String pattern) {
            if (blank(value) || !value.matches(pattern)) return false;
            try {
                Long.parseLong(value);
                return true;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
    }

    public record Sources(
            List<String> greenhouseBoardTokens,
            List<String> leverCompanyIds) {
    }

    public record Candidate(
            String homeCountry,
            List<String> preferredLocations,
            List<String> backendSkills,
            List<String> supportingSkills,
            boolean currentStudent,
            boolean finalYearStudent,
            double commercialJavaYears) {
    }

    public record Http(Duration connectTimeout, Duration responseTimeout, int maxResponseBytes) {
    }

    public record ManualUrl(
            Duration connectTimeout,
            Duration responseTimeout,
            int maxRedirects,
            int maxResponseBytes,
            int maxTitleLength,
            int maxDescriptionLength) {
        public ManualUrl {
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                    || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Manual URL connect timeout must be between 1ns and 30s");
            }
            if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()
                    || responseTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("Manual URL response timeout must be between 1ns and 60s");
            }
            if (maxRedirects < 0 || maxRedirects > 10
                    || maxResponseBytes < 1_024 || maxResponseBytes > 10 * 1_024 * 1_024
                    || maxTitleLength < 1 || maxTitleLength > 1_000
                    || maxDescriptionLength < 40 || maxDescriptionLength > 1_000_000) {
                throw new IllegalArgumentException("Manual URL limits are outside their safe bounds");
            }
        }
    }

    public record Llm(
            boolean enabled,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            Duration connectTimeout,
            Duration responseTimeout,
            Integer maxInputTokens,
            Integer maxOutputTokens,
            int maxRetries,
            BigDecimal requestBudgetUsd,
            BigDecimal dailyBudgetUsd,
            BigDecimal monthlyBudgetUsd,
            BigDecimal inputCostPerMillionTokens,
            BigDecimal outputCostPerMillionTokens) {
        private static final BigDecimal MAX_MONEY = new BigDecimal("1000000");

        public Llm {
            provider = normalize(provider);
            baseUrl = normalize(baseUrl);
            apiKey = apiKey == null ? "" : apiKey.strip();
            model = normalize(model);
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
            responseTimeout = responseTimeout == null ? Duration.ofSeconds(60) : responseTimeout;
            if (connectTimeout.isZero() || connectTimeout.isNegative()
                    || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || responseTimeout.isZero() || responseTimeout.isNegative()
                    || responseTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalArgumentException("LLM timeouts are outside their safe bounds");
            }
            if (maxRetries < 0 || maxRetries > 3) {
                throw new IllegalArgumentException("LLM retry count is outside its safe bounds");
            }
            if (enabled) {
                validateEnabled(provider, baseUrl, apiKey, model, maxInputTokens, maxOutputTokens,
                        maxRetries,
                        requestBudgetUsd, dailyBudgetUsd, monthlyBudgetUsd,
                        inputCostPerMillionTokens, outputCostPerMillionTokens);
            }
        }

        public static Llm disabled() {
            return new Llm(false, "", "", "", "", Duration.ofSeconds(5),
                    Duration.ofSeconds(60), null, null, 1, null, null, null, null, null);
        }

        public URI responsesEndpoint() {
            if (!enabled) throw new IllegalStateException("LLM provider is disabled");
            String normalized = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return URI.create(normalized + "/responses");
        }

        private static void validateEnabled(String provider, String baseUrl, String apiKey,
                                            String model, Integer maxInputTokens,
                                            Integer maxOutputTokens, int maxRetries,
                                            BigDecimal requestBudgetUsd,
                                            BigDecimal dailyBudgetUsd, BigDecimal monthlyBudgetUsd,
                                            BigDecimal inputCost, BigDecimal outputCost) {
            if (!"openai".equals(provider.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Enabled LLM configuration requires a supported provider");
            }
            validateBaseUrl(baseUrl);
            if (apiKey.isBlank() || model.isBlank() || !model.matches("[A-Za-z0-9._:-]{1,200}")) {
                throw new IllegalArgumentException(
                        "Enabled LLM configuration requires an API key and valid model identifier");
            }
            if (maxInputTokens == null || maxInputTokens < 128 || maxInputTokens > 1_000_000
                    || maxOutputTokens == null || maxOutputTokens < 64
                    || maxOutputTokens > 128_000) {
                throw new IllegalArgumentException("LLM token limits are outside their safe bounds");
            }
            validateMoney("request budget", requestBudgetUsd);
            validateMoney("daily budget", dailyBudgetUsd);
            validateMoney("monthly budget", monthlyBudgetUsd);
            validateMoney("input token cost", inputCost);
            validateMoney("output token cost", outputCost);
            BigDecimal maximumExposure = tokenCost(maxInputTokens, inputCost)
                    .add(tokenCost(maxOutputTokens, outputCost))
                    .multiply(BigDecimal.valueOf((long) maxRetries + 1L))
                    .setScale(8, RoundingMode.CEILING);
            if (maximumExposure.compareTo(MAX_MONEY) > 0) {
                throw new IllegalArgumentException(
                        "LLM maximum retry exposure is outside its safe monetary bounds");
            }
            if (requestBudgetUsd.compareTo(dailyBudgetUsd) > 0
                    || dailyBudgetUsd.compareTo(monthlyBudgetUsd) > 0) {
                throw new IllegalArgumentException(
                        "LLM request, daily, and monthly budgets must be ordered from smallest to largest");
            }
        }

        private static void validateBaseUrl(String value) {
            try {
                URI uri = URI.create(value);
                if (uri.isOpaque() || !"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || !"api.openai.com".equalsIgnoreCase(uri.getHost())
                        || uri.getPort() != -1 && uri.getPort() != 443
                        || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null
                        || !("/v1".equals(uri.getRawPath())
                        || "/v1/".equals(uri.getRawPath()))) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Enabled LLM configuration requires a safe HTTPS base URL");
            }
        }

        private static BigDecimal tokenCost(long tokens, BigDecimal perMillion) {
            return BigDecimal.valueOf(tokens).multiply(perMillion)
                    .divide(new BigDecimal("1000000"), 8, RoundingMode.CEILING);
        }

        private static void validateMoney(String label, BigDecimal value) {
            if (value == null || value.signum() <= 0 || value.compareTo(MAX_MONEY) > 0
                    || value.scale() > 8) {
                throw new IllegalArgumentException("LLM " + label + " is outside its safe monetary bounds");
            }
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip();
        }
    }

    public record Scheduling(String fetchCron, String digestCron, int staleDays) {
    }
}
