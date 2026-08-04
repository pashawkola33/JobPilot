package com.jobpilot.sources.workday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.config.JobPilotProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkdayCareerSiteTest {
    @Test
    void parsesTheThreePartEntryAndDerivesEveryUrl() {
        WorkdayCareerSite site = WorkdayCareerSite.parse("db:wd3:DBWebsite");

        assertThat(site.tenant()).isEqualTo("db");
        assertThat(site.shard()).isEqualTo("wd3");
        assertThat(site.careerSite()).isEqualTo("DBWebsite");
        assertThat(site.host()).isEqualTo("db.wd3.myworkdayjobs.com");
        assertThat(site.tenantKey()).isEqualTo("db/DBWebsite");
        assertThat(site.searchUrl())
                .isEqualTo("https://db.wd3.myworkdayjobs.com/wday/cxs/db/DBWebsite/jobs");
        assertThat(site.detailUrl("/job/Bucharest/Junior-Java-Developer_R1"))
                .isEqualTo("https://db.wd3.myworkdayjobs.com/wday/cxs/db/DBWebsite"
                        + "/job/Bucharest/Junior-Java-Developer_R1");
    }

    @Test
    void supportsAnAlternativeShardAndCaseSensitiveCareerSite() {
        WorkdayCareerSite site = WorkdayCareerSite.parse("accenture:wd103:AccentureCareers");

        assertThat(site.host()).isEqualTo("accenture.wd103.myworkdayjobs.com");
        assertThat(site.careerSite()).isEqualTo("AccentureCareers");
    }

    @Test
    void keepsTwoCareerSitesOnOneHostDistinct() {
        WorkdayCareerSite careers = WorkdayCareerSite.parse("lseg:wd3:Careers");
        WorkdayCareerSite graduates = WorkdayCareerSite.parse("lseg:wd3:Graduate_Careers");

        assertThat(careers.host()).isEqualTo(graduates.host());
        assertThat(careers.tenantKey()).isNotEqualTo(graduates.tenantKey());
        assertThat(careers.searchUrl()).isNotEqualTo(graduates.searchUrl());
    }

    @Test
    void normalizesTenantAndShardCaseButNotTheCareerSite() {
        WorkdayCareerSite site = WorkdayCareerSite.parse("  DB : WD3 : DBWebsite  ");

        assertThat(site.configEntry()).isEqualTo("db:wd3:DBWebsite");
    }

    @Test
    void rejectsMalformedEntries() {
        for (String malformed : List.of("", "db", "db:wd3", "db:wd3:site:extra", ":wd3:site",
                "db::site", "db:wd3:", "db:wd3:site name", "db:wd_3:site", "db:w:site",
                "-db:wd3:site", "db-:wd3:site", "db:wd3:-", "db:wd3:site/../other",
                "db.evil:wd3:site", "db:wd3:si te")) {
            assertThatThrownBy(() -> WorkdayCareerSite.parse(malformed))
                    .as(malformed)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenant:shard:careerSite");
        }
        assertThatThrownBy(() -> WorkdayCareerSite.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverEchoesTheOffendingValueBack() {
        assertThatThrownBy(() -> WorkdayCareerSite.parse("secret-looking-value"))
                .hasMessageNotContaining("secret-looking-value");
    }

    @Test
    void rejectsAnExternalPathThatCouldEscapeTheCareerSite() {
        WorkdayCareerSite site = WorkdayCareerSite.parse("db:wd3:DBWebsite");

        for (String hostile : List.of("job/relative", "//evil.test/job", "/job/../../etc",
                "/job/with space", "/job/<script>", "https://evil.test/job", "",
                "/" + "a".repeat(600))) {
            assertThatThrownBy(() -> site.detailUrl(hostile))
                    .as(hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> site.detailUrl(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- configuration binding

    private JobPilotProperties.Sources sources(List<String> workday) {
        return new JobPilotProperties.Sources(List.of(), List.of(), List.of(), List.of(),
                List.of(), workday);
    }

    @Test
    void bindsOneCareerSite() {
        assertThat(sources(List.of("db:wd3:DBWebsite")).workdayCareerSites())
                .containsExactly("db:wd3:DBWebsite");
    }

    @Test
    void bindsSeveralCareerSitesInOrder() {
        assertThat(sources(List.of("db:wd3:DBWebsite", "nxp:wd3:careers", "lseg:wd3:Careers"))
                .workdayCareerSites())
                .containsExactly("db:wd3:DBWebsite", "nxp:wd3:careers", "lseg:wd3:Careers");
    }

    @Test
    void defaultsToNoCareerSites() {
        assertThat(JobPilotProperties.Sources.empty().workdayCareerSites()).isEmpty();
        assertThat(new JobPilotProperties.Sources(List.of(), List.of(), List.of(), List.of(),
                List.of()).workdayCareerSites()).isEmpty();
        assertThat(sources(null).workdayCareerSites()).isEmpty();
    }

    @Test
    void dropsBlankEntriesAndStripsWhitespace() {
        assertThat(sources(List.of("  db:wd3:DBWebsite  ", "", "   ")).workdayCareerSites())
                .containsExactly("db:wd3:DBWebsite");
    }

    @Test
    void rejectsDuplicateCareerSitesIncludingAfterNormalization() {
        assertThatThrownBy(() -> sources(List.of("db:wd3:DBWebsite", "db:wd3:DBWebsite")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
        assertThatThrownBy(() -> sources(List.of("db:wd3:DBWebsite", "DB:WD3:DBWebsite")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void rejectsMalformedConfigurationEntries() {
        assertThatThrownBy(() -> sources(List.of("db:wd3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant:shard:careerSite");
    }

    @Test
    void rejectsMoreCareerSitesThanTheConfiguredCeiling() {
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= JobPilotProperties.Sources.MAX_WORKDAY_CAREER_SITES; i++) {
            tooMany.add("tenant" + i + ":wd3:Careers");
        }

        assertThatThrownBy(() -> sources(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
    }
}
