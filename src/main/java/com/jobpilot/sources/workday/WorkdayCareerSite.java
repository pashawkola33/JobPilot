package com.jobpilot.sources.workday;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One configured Workday career site, expressed as {@code tenant:shard:careerSite}.
 *
 * <p>A single company identifier is not sufficient. One Workday host can serve several
 * distinct career sites (LSEG runs both {@code Careers} and {@code Graduate_Careers}) and
 * the shard differs between tenants ({@code wd3} vs {@code wd103}), so both the hostname
 * and the career-site identifier must be represented.
 *
 * <p>Immutable, and the only place that knows how a Workday URL is built.
 */
public record WorkdayCareerSite(String tenant, String shard, String careerSite) {
    /** DNS label: lowercase, no leading or trailing hyphen. */
    private static final Pattern TENANT = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    /** Workday shards observed in the wild are short lowercase alphanumerics: wd3, wd103. */
    private static final Pattern SHARD = Pattern.compile("[a-z0-9]{2,10}");
    /** Career-site ids are case sensitive: DBWebsite, careers, Careers, Graduate_Careers. */
    private static final Pattern CAREER_SITE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    /**
     * externalPath values come back from the provider and are appended to a validated
     * origin, so they are constrained to an absolute, traversal-free, already-escaped path.
     */
    private static final Pattern EXTERNAL_PATH =
            Pattern.compile("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{1,512}");

    public WorkdayCareerSite {
        if (tenant == null || shard == null || careerSite == null) {
            throw new IllegalArgumentException(malformed());
        }
        tenant = tenant.strip().toLowerCase(Locale.ROOT);
        shard = shard.strip().toLowerCase(Locale.ROOT);
        careerSite = careerSite.strip();
        if (!TENANT.matcher(tenant).matches() || !SHARD.matcher(shard).matches()
                || !CAREER_SITE.matcher(careerSite).matches()) {
            throw new IllegalArgumentException(malformed());
        }
    }

    /** Parses one configuration entry; never echoes the offending value back. */
    public static WorkdayCareerSite parse(String entry) {
        if (entry == null) throw new IllegalArgumentException(malformed());
        String[] parts = entry.strip().split(":", -1);
        if (parts.length != 3) throw new IllegalArgumentException(malformed());
        return new WorkdayCareerSite(parts[0], parts[1], parts[2]);
    }

    /** Canonical round-trip form, used to detect duplicates after normalization. */
    public String configEntry() {
        return tenant + ":" + shard + ":" + careerSite;
    }

    public String host() {
        return tenant + "." + shard + ".myworkdayjobs.com";
    }

    /** Health and {@code provider_tenant} key. Distinct per career site, not per host. */
    public String tenantKey() {
        return tenant + "/" + careerSite;
    }

    public String searchUrl() {
        return "https://" + host() + "/wday/cxs/" + tenant + "/" + careerSite + "/jobs";
    }

    /**
     * Detail URL for a provider-supplied {@code externalPath}. The path is validated rather
     * than trusted so a hostile value cannot escape the career site or the host.
     */
    public String detailUrl(String externalPath) {
        if (externalPath == null || !EXTERNAL_PATH.matcher(externalPath).matches()
                || externalPath.contains("..") || externalPath.contains("//")) {
            throw new IllegalArgumentException("Workday externalPath is not a safe relative path");
        }
        return "https://" + host() + "/wday/cxs/" + tenant + "/" + careerSite + externalPath;
    }

    private static String malformed() {
        return "Workday career sites must be formatted as tenant:shard:careerSite";
    }
}
