package com.jobpilot.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.service.JobNormalizer;
import com.jobpilot.jobs.domain.RawJob;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DeterministicRequirementExtractorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);
    private final JobNormalizer normalizer = new JobNormalizer(clock, new UrlCanonicalizer());
    private final DeterministicRequirementExtractor extractor = new DeterministicRequirementExtractor();

    @Test
    void extractsSkillsEligibilityAndMentorshipSignals() throws Exception {
        var requirements = extractor.extract(job("Java Developer Intern", "strong-java-internship.txt"));

        assertThat(requirements.internshipOrTrainee()).isTrue();
        assertThat(requirements.finalYearMandatory()).isFalse();
        assertThat(requirements.technologies()).contains("Java", "Spring Boot", "REST", "PostgreSQL",
                "JPA", "Maven", "JUnit", "React", "TypeScript", "CI/CD");
        assertThat(requirements.mentorshipSignals()).contains("mentor", "structured mentorship");
        assertThat(requirements.extractionMethod()).isEqualTo("DETERMINISTIC");
    }

    @Test
    void extractsFinalYearExperienceAndLanguages() throws Exception {
        var finalYear = extractor.extract(job("Java Internship", "final-year-only.txt"));
        var senior = extractor.extract(job("Senior Java Developer", "senior-java.txt"));
        var french = extractor.extract(job("Java Intern", "mandatory-french.txt"));

        assertThat(finalYear.finalYearMandatory()).isTrue();
        assertThat(senior.requiredExperienceYears()).isEqualTo(5);
        assertThat(senior.seniority()).isEqualTo("SENIOR");
        assertThat(french.spokenLanguages()).anyMatch(value -> value.startsWith("French"));
    }

    // ---------------------------------------------------------------- seniority: title first

    private String seniorityOf(String title, String description) {
        return extractor.extract(jobWithDescription(title, description)).seniority();
    }

    @Test
    void junctionTitleBeatsAMidLevelProgrammeDescribedInTheBody() {
        assertThat(seniorityOf("Code First Girls Programme - Junior Java Developer",
                "About the programme: the Code First Girls mid-level accelerator programme is "
                        + "designed for women with 1.5+ years of technical experience."))
                .isEqualTo("JUNIOR");
    }

    @Test
    void graduateTitleBeatsSeniorStakeholdersInTheBody() {
        assertThat(seniorityOf("Graduate Talent Scientist",
                "You will be working closely with senior stakeholders to ensure they stay on "
                        + "track with their hiring targets."))
                .isEqualTo("JUNIOR");
    }

    @Test
    void guidanceOfSeniorColleaguesIsNotASeniorRole() {
        assertThat(seniorityOf("QA Automation Engineer (f/m/x)",
                "Design, maintenance, and execution of automated tests under the guidance of "
                        + "senior colleagues. Support the team by participating in load tests."))
                .isNotEqualTo("SENIOR");
    }

    @Test
    void communicatingWithSeniorLeadershipIsNotASeniorRole() {
        assertThat(seniorityOf("Solutions Engineer",
                "Communicating technical strategies clearly to both engineering teams and "
                        + "senior leadership are key to success in this role."))
                .isNotEqualTo("SENIOR");
    }

    @Test
    void aTeamAdvertisingSeveralLevelsAtOnceIsNotASeniorRole() {
        assertThat(seniorityOf("Go (Golang) Software Engineer, Developer Tools",
                "While we are building a full team including senior, junior and entry-level "
                        + "roles, the senior roles require a stronger background."))
                .isNotEqualTo("SENIOR");
    }

    @Test
    void reportingToOrMentoredByASeniorPersonIsNotASeniorRole() {
        assertThat(seniorityOf("Software Engineer", "You will report to a senior engineer."))
                .isNotEqualTo("SENIOR");
        assertThat(seniorityOf("Software Engineer", "Mentorship from senior developers is provided."))
                .isNotEqualTo("SENIOR");
        assertThat(seniorityOf("Software Engineer", "You will collaborate with senior colleagues."))
                .isNotEqualTo("SENIOR");
        assertThat(seniorityOf("Software Engineer", "Working closely with our senior managers."))
                .isNotEqualTo("SENIOR");
    }

    // ---------------------------------------------------------------- genuine detections kept

    @Test
    void keepsGenuineSeniorRoleRequirements() {
        assertThat(seniorityOf("Senior Postgres Engineer", "Own the Postgres platform."))
                .isEqualTo("SENIOR");
        assertThat(seniorityOf("Senior Software Engineer", "Build backend services."))
                .isEqualTo("SENIOR");
        assertThat(seniorityOf("Postgres Engineer",
                "About the role: we are looking for a senior postgres engineer to join our team."))
                .isEqualTo("SENIOR");
        assertThat(seniorityOf("Content Specialist",
                "Modelling for data on strategic data interfaces. Career stage: Senior Associate."))
                .isEqualTo("SENIOR");
    }

    @Test
    void keepsGenuineMiddleAndJuniorTitles() {
        assertThat(seniorityOf("Mid-Level Java Developer", "Build services.")).isEqualTo("MIDDLE");
        assertThat(seniorityOf("Middle Developer", "Build services.")).isEqualTo("MIDDLE");
        assertThat(seniorityOf("Java Developer", "We need a mid-level developer for this team."))
                .isEqualTo("MIDDLE");
        assertThat(seniorityOf("Junior Java Developer", "Build services.")).isEqualTo("JUNIOR");
        assertThat(seniorityOf("Entry-Level Engineer", "Build services.")).isEqualTo("JUNIOR");
        assertThat(seniorityOf("Java Developer Intern", "Build services.")).isEqualTo("INTERNSHIP");
    }

    @Test
    void wordBoundariesStopIncidentalSubstringsFromMatching() {
        // "seniority" is not "senior"; the old contains() check matched it.
        assertThat(seniorityOf("Software Engineer",
                "We value seniority-agnostic hiring and a flat structure.")).isEqualTo("UNKNOWN");
        assertThat(seniorityOf("Software Engineer",
                "Our principality office is in Monaco.")).isEqualTo("UNKNOWN");
        assertThat(seniorityOf("Software Engineer",
                "Amid rapid growth we ship weekly.")).isEqualTo("UNKNOWN");
    }

    @Test
    void seniorityMatchingIsCaseInsensitiveAcrossTitleAndBody() {
        assertThat(seniorityOf("SENIOR SOFTWARE ENGINEER", "x")).isEqualTo("SENIOR");
        assertThat(seniorityOf("senior software engineer", "x")).isEqualTo("SENIOR");
        assertThat(seniorityOf("Engineer", "CAREER STAGE: SENIOR ASSOCIATE")).isEqualTo("SENIOR");
        assertThat(seniorityOf("JUNIOR JAVA DEVELOPER", "x")).isEqualTo("JUNIOR");
    }

    @Test
    void prefersUnknownOverGuessingWhenNoSignalExists() {
        assertThat(seniorityOf("Software Engineer", "Build and ship backend services."))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void doesNotTreatInternalOrInternationalWordsAsInternship() {
        var requirements = extractor.extract(jobWithDescription("Java Developer",
                "Work on internal tools for our international clients. "
                        + "You will collaborate internally with product teams."));

        assertThat(requirements.internshipOrTrainee()).isFalse();
        assertThat(requirements.seniority()).isNotEqualTo("INTERNSHIP");
    }

    @Test
    void keepsRealInternshipAndTraineeSignals() {
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "We welcome interns to our team.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "This internship lasts six months.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "Join as a trainee developer.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "A twelve-month apprenticeship with mentoring.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "Our Java academy starts in October.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "Apply to our graduate program in Bucharest.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "Apply to our graduate programme in Bucharest.")).internshipOrTrainee()).isTrue();
    }

    // ------------------------------------------------- 4B.5B-A: other-person seniority contexts

    @Test
    void mentoringLessSeniorEngineersIsNotASeniorRole() {
        assertThat(seniorityOf("System Software Engineer",
                "You will be discussing design with other team members, mentor less senior "
                        + "engineers, and participate in code reviews."))
                .isNotEqualTo("SENIOR");
    }

    @Test
    void boundedVariantsOfTheLessSeniorPhraseAreAlsoExcluded() {
        assertThat(seniorityOf("Software Engineer", "You will be mentoring less senior colleagues."))
                .isNotEqualTo("SENIOR");
        assertThat(seniorityOf("Software Engineer", "The role mentors less senior developers."))
                .isNotEqualTo("SENIOR");
        assertThat(seniorityOf("Software Engineer", "Coaching less senior team members is expected."))
                .isNotEqualTo("SENIOR");
    }

    @Test
    void escalatingToSeniorLevelEmployeesIsNotASeniorRole() {
        assertThat(seniorityOf("Data Engineering Specialist III",
                "Escalates unsolvable problems beyond individual knowledge and scope to senior "
                        + "level employees proficient in data programming languages."))
                .isNotEqualTo("SENIOR");
    }

    @Test
    void boundedVariantsOfTheEscalationPhraseAreAlsoExcluded() {
        assertThat(seniorityOf("Support Engineer", "Escalate complex issues to senior staff."))
                .isNotEqualTo("SENIOR");
        assertThat(seniorityOf("Support Engineer", "Tickets are escalated to a senior engineer."))
                .isNotEqualTo("SENIOR");
        assertThat(seniorityOf("Support Engineer",
                "Escalation of unresolved incidents goes to our senior on-call responders."))
                .isNotEqualTo("SENIOR");
    }

    @Test
    void genuineSeniorWordingSurvivesTheNewExclusions() {
        assertThat(seniorityOf("AI Engineer",
                "As a senior AI engineer at GitLab, you'll help build the foundation for "
                        + "our transformation.")).isEqualTo("SENIOR");
        assertThat(seniorityOf("Postgres Engineer",
                "About the role: we are looking for a senior engineer to join the team."))
                .isEqualTo("SENIOR");
        assertThat(seniorityOf("Content Specialist", "Career stage: Senior Associate."))
                .isEqualTo("SENIOR");
        assertThat(seniorityOf("Software Engineer",
                "As a mid level backend engineer on the platform team you will ship features."))
                .isEqualTo("MIDDLE");
    }

    /**
     * The exclusions must not become a wildcard: a genuine level word elsewhere in a long
     * description still has to win, and the phrases that look similar but describe the vacancy
     * itself must survive.
     */
    @Test
    void newExclusionsStayBounded() {
        // A genuine senior statement in a later sentence is not suppressed by an earlier
        // escalation clause.
        assertThat(seniorityOf("Engineer",
                "Escalate blocked work to senior staff. We are hiring a senior engineer for "
                        + "this position.")).isEqualTo("SENIOR");
        // "senior level" describing the advertised openings is untouched: no escalation verb.
        assertThat(seniorityOf("Talent Scientist",
                "We have a number of mid-senior level openings in our team."))
                .isEqualTo("SENIOR");
        // Escalation far away from the level word stays outside the bounded window.
        assertThat(seniorityOf("Engineer",
                "Escalation paths are documented in the handbook and reviewed twice a year by "
                        + "the operations guild during their planning cycle. We seek a senior "
                        + "engineer.")).isEqualTo("SENIOR");
        // Plain "senior engineers" without a trigger context is still a signal.
        assertThat(seniorityOf("Engineer", "We are growing our team of senior engineers."))
                .isEqualTo("SENIOR");
    }

    /**
     * Regression fixtures standing in for production jobs 86 and 98. Wording is paraphrased
     * from the single triggering clause of each vacancy; full descriptions are not copied.
     */
    @Test
    void productionRegressionFixturesForJobs86And98() {
        // job 86 - greenhouse/canonical, System Software Engineer, GCC/LLVM toolchain.
        assertThat(seniorityOf("System Software Engineer - compiler, tooling, and ecosystem",
                "You will design and build toolchain components in a future-proof fashion. You "
                        + "will be discussing design with other team members, mentor less senior "
                        + "engineers, and participate in code reviews and design reviews."))
                .isEqualTo("UNKNOWN");
        // job 98 - smartrecruiters/AECOM2, Data Engineering Specialist III.
        assertThat(seniorityOf("Data Engineering Specialist III",
                "Builds and maintains pipelines across varied data sources. Escalates "
                        + "unsolvable problems beyond individual knowledge and scope to senior "
                        + "level employees proficient in data programming languages."))
                .isEqualTo("UNKNOWN");
    }

    private Job jobWithDescription(String title, String description) {
        return normalizer.normalize(new RawJob("fixture", "text-1", "https://example.com/jobs/text-1",
                title, "Example", "Bucharest, Romania", description, null,
                clock.instant(), null, description));
    }

    private Job job(String title, String fixture) throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/fixtures/" + fixture)) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return normalizer.normalize(new RawJob("fixture", fixture, "https://example.com/" + fixture,
                title, "Example", "Bucharest, Romania", text, "Internship",
                clock.instant(), null, text));
    }
}
