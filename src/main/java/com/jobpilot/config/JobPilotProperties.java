package com.jobpilot.config;

import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import com.jobpilot.jobs.domain.RemoteScope;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("jobpilot")
public record JobPilotProperties(
        Telegram telegram,
        Sources sources,
        Eligibility eligibility,
        Candidate candidate,
        Http http,
        ManualUrl manualUrl,
        Llm llm,
        Scheduling scheduling,
        MiniApp miniApp,
        List<String> searchTerms,
        List<String> locations) {

    /** Explicit because the legacy convenience constructor below makes binding ambiguous. */
    @ConstructorBinding
    public JobPilotProperties {
        telegram = telegram == null ? new Telegram("", "") : telegram;
        sources = sources == null ? Sources.empty() : sources;
        eligibility = eligibility == null ? Eligibility.defaults() : eligibility;
        llm = llm == null ? Llm.disabled() : llm;
        miniApp = miniApp == null ? MiniApp.disabled() : miniApp;
        // The Mini App validates initData with the bot token, so it cannot run without one.
        // Checked here because it is the only place both settings are visible.
        if (miniApp.enabled() && (telegram.botToken() == null || telegram.botToken().isBlank())) {
            throw new IllegalArgumentException(
                    "Mini App API requires jobpilot.telegram.bot-token when enabled");
        }
    }

    /** Shape before the Mini App API existed; it stays disabled, so it is inert. */
    public JobPilotProperties(Telegram telegram, Sources sources, Eligibility eligibility,
                              Candidate candidate, Http http, ManualUrl manualUrl, Llm llm,
                              Scheduling scheduling, List<String> searchTerms,
                              List<String> locations) {
        this(telegram, sources, eligibility, candidate, http, manualUrl, llm, scheduling,
                MiniApp.disabled(), searchTerms, locations);
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
            boolean discardPendingOnFirstStart,
            boolean enabled,
            List<String> allowedChatIds,
            boolean matchNotificationsEnabled,
            boolean reviewDigestEnabled,
            int maxJobsPerMessage,
            int maxNoteLength) {

        public static final int MAX_JOBS_PER_MESSAGE_CEILING = 10;
        /** Matches the job_workflow_state note check constraint. */
        public static final int NOTE_LENGTH_CEILING = 1000;
        /**
         * Telegram holds an idle long poll open for the whole timeout, so this must stay
         * strictly below jobpilot.http.response-timeout (20s). A larger value makes the
         * HTTP client abort every idle cycle and retry it as a transport failure.
         */
        public static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofSeconds(15);

        @ConstructorBinding
        public Telegram {
            botUsername = normalizeBotUsername(botUsername);
            pollTimeout = pollTimeout == null ? DEFAULT_POLL_TIMEOUT : pollTimeout;
            pollDelay = pollDelay == null ? Duration.ofSeconds(2) : pollDelay;
            allowedChatIds = normalizeChatIds(allowedChatIds);
            if (pollTimeout.isNegative() || pollTimeout.compareTo(Duration.ofSeconds(50)) > 0
                    || pollDelay.isNegative() || pollDelay.compareTo(Duration.ofMinutes(1)) > 0
                    || pollLimit < 1 || pollLimit > 100
                    || maxUpdateFailures < 1 || maxUpdateFailures > 20) {
                throw new IllegalArgumentException("Telegram polling limits are outside their safe bounds");
            }
            if (maxJobsPerMessage < 1 || maxJobsPerMessage > MAX_JOBS_PER_MESSAGE_CEILING
                    || maxNoteLength < 1 || maxNoteLength > NOTE_LENGTH_CEILING) {
                throw new IllegalArgumentException(
                        "Telegram review bounds are outside their safe range");
            }
            if (commandsEnabled && (blank(botToken) || !validBotUsername(botUsername)
                    || !validChatId(allowedChatId)
                    || !validUserId(allowedUserId))) {
                throw new IllegalArgumentException(
                        "Telegram commands require a bot token, bot username, and explicit chat and user authorization");
            }
            if (enabled && blank(botToken)) {
                throw new IllegalArgumentException("Telegram bot requires a bot token when enabled");
            }
            if (enabled && allowedChatIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Telegram bot requires at least one allowed numeric chat id when enabled");
            }
        }

        public Telegram(String botToken, String channelId) {
            this(botToken, channelId, "", false, "", "", DEFAULT_POLL_TIMEOUT,
                    Duration.ofSeconds(2), 50, 3, true, false, List.of(), true, true, 5, 500);
        }

        /** Legacy shape: everything before the review bot, with the review bot switched off. */
        public Telegram(String botToken, String channelId, String botUsername,
                        boolean commandsEnabled, String allowedChatId, String allowedUserId,
                        Duration pollTimeout, Duration pollDelay, int pollLimit,
                        int maxUpdateFailures, boolean discardPendingOnFirstStart) {
            this(botToken, channelId, botUsername, commandsEnabled, allowedChatId, allowedUserId,
                    pollTimeout, pollDelay, pollLimit, maxUpdateFailures,
                    discardPendingOnFirstStart, false, List.of(), true, true, 5, 500);
        }

        /** Review bot shape: the six review settings on top of safe polling defaults. */
        public Telegram(String botToken, boolean enabled, List<String> allowedChatIds,
                        boolean matchNotificationsEnabled, boolean reviewDigestEnabled,
                        int maxJobsPerMessage, int maxNoteLength) {
            this(botToken, "", "", false, "", "", DEFAULT_POLL_TIMEOUT, Duration.ofSeconds(2),
                    50, 3, true, enabled, allowedChatIds, matchNotificationsEnabled,
                    reviewDigestEnabled, maxJobsPerMessage, maxNoteLength);
        }

        /** Explicit review-bot switch. Disabled by default; no token is required while off. */
        public boolean enabled() {
            return enabled;
        }

        /** The legacy broadcast channel is driven purely by its own two settings. */
        public boolean channelConfigured() {
            return !blank(botToken) && !blank(channelId);
        }

        /** Long polling runs for either the legacy command surface or the review bot. */
        public boolean pollingEnabled() {
            return commandsEnabled || enabled;
        }

        public boolean allowsChat(long chatId) {
            return allowedChatIds.contains(Long.toString(chatId));
        }

        /** Never let a bot token reach a log, a stack trace, or an actuator dump. */
        @Override
        public String toString() {
            return "Telegram[enabled=" + enabled + ", botToken=" + (blank(botToken) ? "<empty>" : "<redacted>")
                    + ", allowedChatIds=" + allowedChatIds.size() + " configured"
                    + ", commandsEnabled=" + commandsEnabled
                    + ", matchNotificationsEnabled=" + matchNotificationsEnabled
                    + ", reviewDigestEnabled=" + reviewDigestEnabled
                    + ", maxJobsPerMessage=" + maxJobsPerMessage
                    + ", maxNoteLength=" + maxNoteLength
                    + ", pollTimeout=" + pollTimeout + "]";
        }

        private static List<String> normalizeChatIds(List<String> values) {
            if (values == null) return List.of();
            List<String> normalized = new java.util.ArrayList<>();
            for (String value : values) {
                String candidate = value == null ? "" : value.strip();
                if (candidate.isEmpty()) continue;
                if (!validChatId(candidate)) {
                    throw new IllegalArgumentException(
                            "Telegram allowed chat ids must be numeric Telegram chat identifiers");
                }
                if (normalized.contains(candidate)) {
                    throw new IllegalArgumentException(
                            "Telegram allowed chat ids must not contain duplicates");
                }
                normalized.add(candidate);
            }
            return List.copyOf(normalized);
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
            List<String> leverCompanyIds,
            List<String> ashbyBoardNames,
            List<String> recruiteeCompanyIds,
            List<String> smartrecruitersCompanyIdentifiers,
            List<String> workdayCareerSites) {

        private static final int MAX_SMARTRECRUITERS_COMPANIES = 100;
        public static final int MAX_WORKDAY_CAREER_SITES = 25;

        @ConstructorBinding
        public Sources {
            greenhouseBoardTokens = copyAndValidate("Greenhouse", greenhouseBoardTokens);
            leverCompanyIds = copyAndValidate("Lever", leverCompanyIds);
            ashbyBoardNames = copyAndValidate("Ashby", ashbyBoardNames);
            recruiteeCompanyIds = copyAndValidate("Recruitee", recruiteeCompanyIds);
            smartrecruitersCompanyIdentifiers = copyAndValidate(
                    "SmartRecruiters", smartrecruitersCompanyIdentifiers);
            workdayCareerSites = normalizeWorkdaySites(workdayCareerSites);
            if (smartrecruitersCompanyIdentifiers.size() > MAX_SMARTRECRUITERS_COMPANIES) {
                throw new IllegalArgumentException(
                        "SmartRecruiters supports at most 100 configured companies");
            }
            if (new HashSet<>(smartrecruitersCompanyIdentifiers).size()
                    != smartrecruitersCompanyIdentifiers.size()) {
                throw new IllegalArgumentException(
                        "SmartRecruiters company identifiers must not contain duplicates");
            }
        }

        /** Shape before Workday existed; Workday stays empty, so it is inert. */
        public Sources(List<String> greenhouseBoardTokens, List<String> leverCompanyIds,
                       List<String> ashbyBoardNames, List<String> recruiteeCompanyIds,
                       List<String> smartrecruitersCompanyIdentifiers) {
            this(greenhouseBoardTokens, leverCompanyIds, ashbyBoardNames, recruiteeCompanyIds,
                    smartrecruitersCompanyIdentifiers, List.of());
        }

        public static Sources empty() {
            return new Sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        /**
         * Each entry is {@code tenant:shard:careerSite}. A single company identifier is not
         * enough: one Workday host can serve several career sites, and the shard differs
         * between tenants. Parsing is delegated so the adapter and the binder agree on the
         * one grammar.
         */
        private static List<String> normalizeWorkdaySites(List<String> values) {
            if (values == null) return List.of();
            List<String> normalized = new java.util.ArrayList<>();
            for (String value : values) {
                if (value == null || value.isBlank()) continue;
                String entry = value.strip();
                // Throws IllegalArgumentException with a safe message when malformed.
                com.jobpilot.sources.workday.WorkdayCareerSite parsed =
                        com.jobpilot.sources.workday.WorkdayCareerSite.parse(entry);
                String canonical = parsed.configEntry();
                if (normalized.contains(canonical)) {
                    throw new IllegalArgumentException(
                            "Workday career sites must not contain duplicates");
                }
                normalized.add(canonical);
            }
            if (normalized.size() > MAX_WORKDAY_CAREER_SITES) {
                throw new IllegalArgumentException(
                        "Workday supports at most " + MAX_WORKDAY_CAREER_SITES + " configured career sites");
            }
            return List.copyOf(normalized);
        }

        private static List<String> copyAndValidate(String provider, List<String> values) {
            if (values == null) return List.of();
            for (String value : values) {
                if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}")) {
                    throw new IllegalArgumentException(provider
                            + " tenant must match [a-zA-Z0-9][a-zA-Z0-9._-]{0,62}");
                }
            }
            return List.copyOf(values);
        }
    }

    public record Eligibility(
            String targetCity,
            String targetCountry,
            boolean includeIlfov,
            boolean acceptBucharestOnsite,
            boolean acceptBucharestHybrid,
            boolean acceptBucharestRemote,
            boolean acceptRemoteFromRomania,
            boolean rejectUnknownRemoteScope,
            List<RemoteScope> acceptedRemoteRegions) {

        public Eligibility {
            targetCity = normalizeOrDefault(targetCity, "Bucharest");
            targetCountry = normalizeOrDefault(targetCountry, "Romania");
            acceptedRemoteRegions = acceptedRemoteRegions == null || acceptedRemoteRegions.isEmpty()
                    ? defaultRegions() : List.copyOf(acceptedRemoteRegions);
            if (acceptedRemoteRegions.stream().anyMatch(scope -> scope == null
                    || scope == RemoteScope.UNKNOWN || scope == RemoteScope.COUNTRY_RESTRICTED
                    || scope == RemoteScope.REGION_RESTRICTED)) {
                throw new IllegalArgumentException("Accepted remote regions must include Romania-compatible scopes only");
            }
        }

        public static Eligibility defaults() {
            return new Eligibility("Bucharest", "Romania", false,
                    true, true, true, true, true, defaultRegions());
        }

        private static List<RemoteScope> defaultRegions() {
            return List.of(RemoteScope.ROMANIA, RemoteScope.EU, RemoteScope.EEA,
                    RemoteScope.EUROPE, RemoteScope.EMEA, RemoteScope.WORLDWIDE);
        }

        private static String normalizeOrDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.strip();
        }
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
        /** Smallest useful ATS payload bound. */
        public static final int MIN_RESPONSE_BYTES = 1_048_576;
        /** Hard ceiling: one bounded buffer per in-flight fetch must stay affordable. */
        public static final int MAX_RESPONSE_BYTES = 33_554_432;

        public Http {
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                    || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()
                    || responseTimeout.compareTo(Duration.ofSeconds(90)) > 0
                    || responseTimeout.compareTo(connectTimeout) <= 0) {
                throw new IllegalArgumentException("External HTTP timeouts are outside their safe bounds");
            }
            // Fail closed and name the range: an out-of-range limit is never clamped.
            if (maxResponseBytes < MIN_RESPONSE_BYTES || maxResponseBytes > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException(
                        "jobpilot.http.max-response-bytes must be between " + MIN_RESPONSE_BYTES
                                + " and " + MAX_RESPONSE_BYTES + " bytes, but was " + maxResponseBytes);
            }
        }
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

    /**
     * Telegram Mini App API. Disabled by default, and an empty allow-list is a deny-all:
     * there is no configuration in which this surface is open to everyone.
     *
     * <p>Telegram user ids are not the same thing as the bot's allowed chat ids, so this
     * keeps its own explicit list rather than reusing {@link Telegram#allowedChatIds()}.
     */
    public record MiniApp(
            boolean enabled,
            List<String> allowedUserIds,
            Duration maxAuthAge) {

        /** Telegram tokens are long-lived; anything beyond a day is not a fresh launch. */
        public static final Duration MAX_AUTH_AGE_CEILING = Duration.ofHours(24);
        public static final Duration DEFAULT_MAX_AUTH_AGE = Duration.ofHours(1);

        @ConstructorBinding
        public MiniApp {
            allowedUserIds = normalizeUserIds(allowedUserIds);
            maxAuthAge = maxAuthAge == null ? DEFAULT_MAX_AUTH_AGE : maxAuthAge;
            if (maxAuthAge.isZero() || maxAuthAge.isNegative()
                    || maxAuthAge.compareTo(MAX_AUTH_AGE_CEILING) > 0) {
                throw new IllegalArgumentException(
                        "jobpilot.mini-app.max-auth-age must be positive and at most 24h");
            }
            if (enabled && allowedUserIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Mini App API requires at least one allowed numeric Telegram user id when enabled");
            }
        }

        public static MiniApp disabled() {
            return new MiniApp(false, List.of(), DEFAULT_MAX_AUTH_AGE);
        }

        /** Deny-all when empty; membership is never inferred from anything else. */
        public boolean allows(long userId) {
            return allowedUserIds.contains(Long.toString(userId));
        }

        @Override
        public String toString() {
            return "MiniApp[enabled=" + enabled + ", allowedUserIds=" + allowedUserIds.size()
                    + " configured, maxAuthAge=" + maxAuthAge + "]";
        }

        private static List<String> normalizeUserIds(List<String> values) {
            if (values == null) return List.of();
            List<String> normalized = new java.util.ArrayList<>();
            for (String value : values) {
                String candidate = value == null ? "" : value.strip();
                if (candidate.isEmpty()) continue;
                if (!validUserId(candidate)) {
                    throw new IllegalArgumentException(
                            "Mini App allowed user ids must be positive numeric Telegram user identifiers");
                }
                if (normalized.contains(candidate)) {
                    throw new IllegalArgumentException(
                            "Mini App allowed user ids must not contain duplicates");
                }
                normalized.add(candidate);
            }
            return List.copyOf(normalized);
        }

        private static boolean validUserId(String value) {
            if (!value.matches("[1-9]\\d{0,18}")) return false;
            try {
                Long.parseLong(value);
                return true;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
    }
}
