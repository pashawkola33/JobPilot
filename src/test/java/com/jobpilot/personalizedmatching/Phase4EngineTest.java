package com.jobpilot.personalizedmatching;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.jobs.domain.*;
import com.jobpilot.searchprofile.SearchProfile;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class Phase4EngineTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Instant now=Instant.parse("2026-10-20T00:00:00Z");
    final VacancyFactExtractor extractor=new VacancyFactExtractor();
    final PersonalizedMatcher matcher=new PersonalizedMatcher();
    SearchProfile profile(String title,String skills,String extra) throws Exception {
        return json.readValue("{\"desiredTitles\":[\""+title+"\"],\"technicalSkills\":["+skills+"],\"targetRegions\":[\"Romania\"],\"workFormats\":[\"REMOTE\"],\"currentCountry\":\"Romania\",\"experienceYears\":0,"+extra+"}",SearchProfile.class);
    }
    VacancyFacts facts(String title,String body) {
        Job j=new Job("fixture","one","https://example.org/jobs/one",title,"Example","Romania",RemoteType.REMOTE,"FULL_TIME",body,now,null,"hash","hash","hash",now);
        return extractor.extract(j,null);
    }
    Job legacyOfficeJob(String body) {
        return new Job("fixture","office","https://example.org/office","Data analyst","Example","Bucharest, Romania",RemoteType.ONSITE,"FULL_TIME",body,now,null,"hash","hash","hash",now);
    }
    SearchProfile abroad() throws Exception {
        return json.readValue("{\"desiredTitles\":[\"Data analyst\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Germany\"],\"currentCountry\":\"Germany\",\"currentCity\":\"Berlin\",\"experienceYears\":0,\"relocation\":\"NO\"}",SearchProfile.class);
    }
    @Test void explicitWorldwideRemoteDoesNotInheritTheLegacyOfficeInference() throws Exception {
        var j=legacyOfficeJob("Required skills: SQL. Remote worldwide. No prior experience required.");
        var f=extractor.extract(j,null);
        assertThat(f.workFormat()).isEqualTo("REMOTE");
        assertThat(f.requirements()).noneMatch(r->r.kind().equals("OFFICE"));
        assertThat(matcher.evaluate(abroad(),f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.ELIGIBLE);
        assertThat(j.getRemoteType()).isEqualTo(RemoteType.ONSITE);
    }
    @Test void conflictingProviderOfficeAndWorldwideRemoteRemainUnknown() throws Exception {
        var j=legacyOfficeJob("Required skills: SQL. Remote worldwide. No prior experience required.");
        for(String format:java.util.List.of("ONSITE","HYBRID")) {
            var raw=new RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),j.getDescription(),j.getEmploymentType(),now,null,"public",new RawLocationData(format,null,null,null,null,null,null));
            var f=extractor.extract(j,raw);
            assertThat(f.workFormat()).as(format).isEqualTo("UNKNOWN");
            assertThat(f.requirements()).noneMatch(r->r.kind().equals("OFFICE"));
            assertThat(matcher.evaluate(abroad(),f,now).eligibility()).as(format).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        }
    }
    @Test void contradictoryLocalWorkClauseCannotBeOverriddenByWorldwideRemote() throws Exception {
        var f=extractor.extract(legacyOfficeJob("Required skills: SQL. Remote worldwide. Work from our office. No prior experience required."),null);
        assertThat(f.workFormat()).isEqualTo("UNKNOWN");
        assertThat(matcher.evaluate(abroad(),f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
    }
    @Test void negatedWorldwideRemoteNeverGrantsRemoteEligibility() throws Exception {
        var f=extractor.extract(legacyOfficeJob("Required skills: SQL. Remote worldwide is not available. No prior experience required."),null);
        assertThat(f.workFormat()).isEqualTo("ONSITE");
        assertThat(matcher.evaluate(abroad(),f,now).eligibility()).isNotEqualTo(PersonalizedMatcher.Eligibility.ELIGIBLE);
    }
    @Test void conflictingFormatDoesNotEraseAnIndependentLanguageBlocker() throws Exception {
        var j=legacyOfficeJob("Required skills: SQL. Remote worldwide. Hybrid role. English B2 required.");
        var p=json.readValue(json.writeValueAsString(abroad()).replace("\"languages\":[]","\"languages\":[{\"language\":\"English\",\"proficiency\":\"A1\"}]"),SearchProfile.class);
        var f=extractor.extract(j,null);
        assertThat(f.workFormat()).isEqualTo("UNKNOWN");
        assertThat(matcher.evaluate(p,f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
    }
    @Test void recognizedLanguageOrRemoteDoesNotHideAnUnsupportedMandatoryClause() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"languages\":[{\"language\":\"English\",\"proficiency\":\"C1\"}]");
        for(String body:java.util.List.of("English B2 and advanced Excel required. Remote worldwide.","Remote worldwide, security clearance required.","English B2 with security clearance required. Remote worldwide.","Current student and security clearance required. Remote worldwide.","English B2 required, Java is not mandatory. Remote worldwide."))
            assertThat(matcher.evaluate(p,facts("Data analyst",body),now).eligibility()).as(body).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
    }
    @Test void professionalRangesNeverUseGeneralYearsAndLicenceIsNotADegree() throws Exception {
        var p=profile("Data analyst","","\"experienceYears\":5,\"experienceLevel\":\"NO_PROFESSIONAL_EXPERIENCE\",\"education\":\"no degree\"");
        for(String qualifier:java.util.List.of("professional","commercial","industry"))
            assertThat(matcher.evaluate(p,facts("Data analyst","3-5 years of "+qualifier+" experience required. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
        assertThat(matcher.evaluate(p,facts("Data analyst","Driving licence required. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        assertThat(matcher.evaluate(p,facts("Data analyst","Scrum Master certification required. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
    }
    @Test void oneLargeSkillListHasABoundedUnknownProjection() throws Exception {
        String skills=java.util.stream.IntStream.range(0,260).mapToObj(n->"skill"+n).collect(java.util.stream.Collectors.joining(", "));
        var f=facts("Data analyst","Required skills: "+skills+". Remote worldwide.");
        assertThat(f.requirements()).hasSizeLessThanOrEqualTo(200);
        var result=matcher.evaluate(profile("Data analyst","","\"languages\":[]"),f,now);
        assertThat(result.reasons()).hasSizeLessThanOrEqualTo(256);
        assertThat(result.reasons()).anyMatch(reason->reason.code().equals("MANDATORY_REQUIREMENT_UNPARSED"));
        assertThat(result.eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
    }
    @Test void providerExperienceFactsCannotBeDiscardedAndSponsorshipMustBeCountrySpecific() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"workAuthorization\":\"REQUIRES_SPONSORSHIP\"");
        Job j=new Job("fixture","career","https://example.org/career","Data analyst","Example","Romania",RemoteType.REMOTE,"FULL_TIME","Required skills: SQL. Remote worldwide.",now,null,"hash","hash","hash",now);
        var raw=new RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),j.getDescription(),j.getEmploymentType(),now,null,"public",RawLocationData.empty(),"Example",new RawCareerData(null,3d,5d,true,"3 years of professional experience"));
        assertThat(matcher.evaluate(p,extractor.extract(j,raw),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
        var f=facts("Data analyst","No sponsorship available in Germany. Remote worldwide.");
        assertThat(matcher.evaluate(p,f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        var scoped=profile("Data analyst","\"SQL\"","\"authorizationByCountry\":[{\"country\":\"DE\",\"status\":\"REQUIRES_SPONSORSHIP\"}]");
        assertThat(matcher.evaluate(scoped,f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
    }
    @Test void negatedEducationAndStructuredSkillExperienceCannotProvePossession() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"experienceYears\":5,\"education\":\"I do not have a Bachelor degree in Marketing\"");
        assertThat(matcher.evaluate(p,facts("Data analyst","Bachelor degree in Marketing required. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        Job j=new Job("fixture","scoped-career","https://example.org/scoped-career","Data analyst","Example","Romania",RemoteType.REMOTE,"FULL_TIME","Required skills: SQL. Remote worldwide.",now,null,"hash","hash","hash",now);
        var raw=new RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),j.getDescription(),j.getEmploymentType(),now,null,"public",RawLocationData.empty(),"Example",new RawCareerData(null,3d,5d,true,"3 years of experience with Python"));
        assertThat(matcher.evaluate(p,extractor.extract(j,raw),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
    }
    @Test void oneGeneralEngineRanksSoftwareDataMarketingFinanceAndLogisticsIndependently() throws Exception {
        String[] titles={"Java developer","Data analyst","Marketing assistant","Finance analyst","Logistics coordinator"};
        String[] skills={"Java","Python","SEO","Accounting","Inventory management"};
        for(int i=0;i<titles.length;i++) {
            var p=profile(titles[i],"\""+skills[i]+"\"","\"languages\":[]");
            var own=matcher.evaluate(p,facts(titles[i],"Required skills: "+skills[i]+". Remote worldwide."),now);
            for(int other=0;other<titles.length;other++)if(other!=i)
                assertThat(own.compatibilityScore()).isGreaterThan(matcher.evaluate(p,facts(titles[other],"Required skills: "+skills[other]+". Remote worldwide."),now).compatibilityScore());
        }
    }
    @Test void negatedNumericExperienceCannotBecomeAMandatoryMinimum() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"experienceYears\":0");
        for(String clause:java.util.List.of("3 years of experience is not required.","3 years of professional experience is not mandatory.","No 3 years of experience required.")) {
            var result=matcher.evaluate(p,facts("Data analyst",clause+" Remote worldwide."),now);
            assertThat(result.eligibility()).as(clause).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
            assertThat(result.reasons()).noneMatch(reason->reason.status().equals("BLOCKED"));
        }
        assertThat(matcher.evaluate(p,facts("Data analyst","No prior experience required. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.ELIGIBLE);
    }
    @Test void sameVacancyDifferentCandidatesAndExtraSkillsCannotLowerCompatibility() throws Exception {
        var f=facts("Java developer","Required skills: Java, SQL. No prior experience required. Remote worldwide.");
        var a=profile("Java developer","\"Java\",\"SQL\"","\"languages\":[]");
        var b=profile("Marketing assistant","\"SEO\"","\"languages\":[]");
        var extra=profile("Java developer","\"Java\",\"SQL\",\"Knitting\",\"SEO\"","\"languages\":[]");
        assertThat(matcher.evaluate(a,f,now).compatibilityScore()).isGreaterThan(matcher.evaluate(b,f,now).compatibilityScore());
        assertThat(matcher.evaluate(a,f,now).compatibilityScore()).isEqualTo(matcher.evaluate(extra,f,now).compatibilityScore());
    }
    @Test void requiredLanguageGivesDifferentEligibilityWhileUnknownDoesNotBecomeAbsent() throws Exception {
        var f=facts("Marketing assistant","English B2 required. Required skills: SEO. Remote worldwide. No prior experience required.");
        var good=profile("Marketing assistant","\"SEO\"","\"languages\":[{\"language\":\"English\",\"proficiency\":\"C1\"}]");
        var low=profile("Marketing assistant","\"SEO\"","\"languages\":[{\"language\":\"English\",\"proficiency\":\"A2\"}]");
        var missing=profile("Marketing assistant","","\"languages\":[]");
        assertThat(matcher.evaluate(good,f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.ELIGIBLE);
        assertThat(matcher.evaluate(low,f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
        assertThat(matcher.evaluate(low,f,now).recommendationScore()).isNull();
        assertThat(matcher.evaluate(missing,f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        assertThat(matcher.evaluate(missing,f,now).reasons()).anyMatch(x->x.code().equals("REQUIRED_SKILL_UNCONFIRMED"));
    }
    @Test void hardExperienceAndAuthorizationCannotBeOverriddenByHighScore() throws Exception {
        var f=facts("Data analyst","Required skills: SQL. Minimum 3 years of experience required. Work authorization in Romania required. Remote worldwide.");
        var p=profile("Data analyst","\"SQL\"","\"workAuthorization\":\"AUTHORIZED\"");
        var result=matcher.evaluate(p,f,now);
        assertThat(result.eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
        assertThat(result.reasons()).anyMatch(x->x.code().equals("EXPERIENCE_BELOW_REQUIRED"));
        assertThat(result.reasons()).anyMatch(x->x.code().equals("WORK_AUTHORIZATION_UNKNOWN"));
    }
    @Test void preferredSkillsAndExperienceDoNotCreateHardBlockers() throws Exception {
        var f=facts("Logistics coordinator","Preferred skills: SAP. Preferably 5 years of experience. Remote worldwide.");
        var p=profile("Logistics coordinator","","\"languages\":[]");
        assertThat(matcher.evaluate(p,f,now).eligibility()).isNotEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
    }
    @Test void remoteLocationAndPermitAreIndependentAndScoped() throws Exception {
        var f=facts("Finance assistant","Remote in Romania only. Work authorization in Romania required. No prior experience required.");
        var p=profile("Finance assistant","","\"authorizationByCountry\":[{\"country\":\"RO\",\"status\":\"AUTHORIZED\"}]");
        var result=matcher.evaluate(p,f,now);
        assertThat(result.reasons()).anyMatch(x->x.code().equals("WORK_AUTHORIZATION_SATISFIED"));
        assertThat(result.reasons()).anyMatch(x->x.code().equals("LOCATION_SATISFIED"));
        var unknown=profile("Finance assistant","","\"workAuthorization\":\"AUTHORIZED\"");
        assertThat(matcher.evaluate(unknown,f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
    }
    @Test void missingJobFactsAndScopedExperienceRemainUnknown() throws Exception {
        var p=profile("Business operations","","\"languages\":[]");
        var result=matcher.evaluate(p,facts("Business operations","Join our team."),now);
        assertThat(result.eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        assertThat(result.evidenceCoverage()).isLessThan(100);
        assertThat(matcher.evaluate(p,facts("Business operations","Minimum 3 years of Python experience required. Remote worldwide."),now).reasons()).anyMatch(x->x.code().equals("EXPERIENCE_SCOPE_UNKNOWN"));
    }
    @Test void alternativesAndNegationDoNotManufactureBlockers() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"languages\":[{\"language\":\"English\",\"proficiency\":\"C1\"},{\"language\":\"French\",\"proficiency\":\"A1\"}]");
        var f=facts("Data analyst","English B2 or French C1 required. Remote worldwide.");
        assertThat(matcher.evaluate(p,f,now).eligibility()).isNotEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
        var negative=facts("Data analyst","French C1 is not required. Required skills: SQL. Remote worldwide.");
        assertThat(matcher.evaluate(p,negative,now).eligibility()).isNotEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
    }
    @Test void comparableSalaryChangesPreferenceFitWithoutChangingEligibility() throws Exception {
        var low=profile("Data analyst","\"SQL\"","\"salary\":{\"amount\":1000,\"currency\":\"EUR\",\"period\":\"MONTH\",\"basis\":\"GROSS\"}");
        var high=profile("Data analyst","\"SQL\"","\"salary\":{\"amount\":5000,\"currency\":\"EUR\",\"period\":\"MONTH\",\"basis\":\"GROSS\"}");
        var f=facts("Data analyst","Required skills: SQL. Remote worldwide. Salary: EUR 3000/month gross.");
        assertThat(matcher.evaluate(low,f,now).compatibilityScore()).isGreaterThan(matcher.evaluate(high,f,now).compatibilityScore());
        assertThat(matcher.evaluate(low,f,now).eligibility()).isEqualTo(matcher.evaluate(high,f,now).eligibility());
    }
    @Test void explicitComparableEducationCanSatisfyButAnUnstatedDegreeStaysUnknown() throws Exception {
        var good=profile("Marketing assistant","","\"education\":\"Bachelor degree in Marketing\"");
        var unknown=profile("Marketing assistant","","\"education\":\"Marketing graduate\"");
        var f=facts("Marketing assistant","Bachelor degree in Marketing required. Remote worldwide.");
        assertThat(matcher.evaluate(good,f,now).reasons()).anyMatch(x->x.code().equals("EDUCATION_SATISFIED"));
        assertThat(matcher.evaluate(unknown,f,now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
    }
    @Test void anExperienceRangeUsesItsMinimumAndMixedLanguageScopeCannotGrantEligibility() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"experienceYears\":4,\"languages\":[{\"language\":\"English\",\"proficiency\":\"A1\"},{\"language\":\"French\",\"proficiency\":\"C1\"}]");
        assertThat(matcher.evaluate(p,facts("Data analyst","3-5 years of experience required. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.ELIGIBLE);
        assertThat(matcher.evaluate(p,facts("Data analyst","English and French B2 required. Remote worldwide."),now).eligibility()).isNotEqualTo(PersonalizedMatcher.Eligibility.ELIGIBLE);
    }
    @Test void aMandatorySynonymAndExperienceWithASkillAreNotSilentlySatisfied() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"experienceYears\":6");
        assertThat(matcher.evaluate(p,facts("Data analyst","English B2 is essential. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        assertThat(matcher.evaluate(p,facts("Data analyst","Minimum 3 years of experience with Python required. Remote worldwide."),now).reasons()).anyMatch(x->x.code().equals("EXPERIENCE_SCOPE_UNKNOWN"));
    }
    @Test void unknownSalaryBasisStaysNeutralAndStructuredLocationsAreAlternatives() throws Exception {
        var low=profile("Data analyst","\"SQL\"","\"salary\":{\"amount\":1000,\"currency\":\"EUR\",\"period\":\"MONTH\"}");
        var high=profile("Data analyst","\"SQL\"","\"salary\":{\"amount\":5000,\"currency\":\"EUR\",\"period\":\"MONTH\"}");
        var f=facts("Data analyst","Required skills: SQL. Remote worldwide. Salary: EUR 3000/month gross.");
        assertThat(matcher.evaluate(low,f,now).compatibilityScore()).isEqualTo(matcher.evaluate(high,f,now).compatibilityScore());
        Job j=new Job("fixture","regions","https://example.org/regions","Data analyst","Example","Remote",RemoteType.REMOTE,"FULL_TIME","No prior experience required.",now,null,"hash","hash","hash",now);
        RawJob raw=new RawJob("fixture","regions",j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),j.getDescription(),j.getEmploymentType(),now,null,"public",new RawLocationData("REMOTE",null,null,null,java.util.List.of("Romania","Germany"),null,null));
        var p=profile("Data analyst","","\"languages\":[]");
        assertThat(matcher.evaluate(p,extractor.extract(j,raw),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.ELIGIBLE);
    }
    @Test void languageLevelWordingAndNativeClaimsNeverGrantUnsupportedEligibility() throws Exception {
        var low=profile("Marketing assistant","","\"languages\":[{\"language\":\"English\",\"proficiency\":\"A1\"}]");
        for(String requirement:java.util.List.of("English level B2 required.","B2 English required.","English (B2) mandatory."))
            assertThat(matcher.evaluate(low,facts("Marketing assistant",requirement+" Remote worldwide."),now).eligibility()).as(requirement).isEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
        var advanced=profile("Marketing assistant","","\"languages\":[{\"language\":\"English\",\"proficiency\":\"C2\"}]");
        assertThat(matcher.evaluate(advanced,facts("Marketing assistant","Native English required. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        assertThat(matcher.evaluate(low,facts("Marketing assistant","Excellent English required. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
    }
    @Test void unspecifiedRequirementModalityAndClosedPostingsDoNotBecomeEligible() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"experienceYears\":0");
        assertThat(matcher.evaluate(p,facts("Data analyst","3 years of experience. Remote worldwide."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.UNKNOWN);
        assertThat(matcher.evaluate(p,facts("Data analyst","Required skills: SQL. Remote worldwide. Position closed; no longer accepting applications."),now).eligibility()).isEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
        var senior=profile("Data analyst","\"SQL\"","\"experienceYears\":6");
        var preferred=facts("Data analyst","Preferably 3 years of experience. Remote worldwide.");
        assertThat(matcher.evaluate(senior,preferred,now).compatibilityScore()).isGreaterThan(matcher.evaluate(p,preferred,now).compatibilityScore());
        assertThat(matcher.evaluate(p,preferred,now).eligibility()).isNotEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
    }
    @Test void savedSeniorityAndDesiredTitleSeniorityAffectSoftCompatibility() throws Exception {
        var junior=profile("Junior data analyst","\"SQL\"","\"experienceLevel\":\"ENTRY\"");
        var senior=profile("Senior data analyst","\"SQL\"","\"experienceLevel\":\"SENIOR\",\"experienceYears\":6");
        var f=facts("Senior data analyst","Required skills: SQL. Remote worldwide.");
        assertThat(matcher.evaluate(senior,f,now).compatibilityScore()).isGreaterThan(matcher.evaluate(junior,f,now).compatibilityScore());
        assertThat(matcher.evaluate(junior,f,now).eligibility()).isNotEqualTo(PersonalizedMatcher.Eligibility.INELIGIBLE);
    }
    @Test void freshnessDoesNotChangeFactualEligibilityOrCompatibility() throws Exception {
        var p=profile("Data analyst","\"SQL\"","\"languages\":[]");
        var f=facts("Data analyst","Required skills: SQL. Remote worldwide. No prior experience required.");
        assertThat(matcher.evaluate(p,f,now)).isEqualTo(matcher.evaluate(p,f,now.plusSeconds(86400*30)));
    }
}
