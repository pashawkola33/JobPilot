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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    // ---------------------------------------------------------------- extractor hygiene

    private List<String> technologiesOf(String description) {
        return extractor.extract(jobWithDescription("Software Engineer", description))
                .technologies();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "We build REST API services",
        "Design REST APIs for partners",
        "Experience with REST service design",
        "Owns several REST services",
        "Implements a REST endpoint",
        "Documented REST endpoints",
        "Writes a REST controller",
        "Several REST controllers in Spring",
        "Strong RESTful experience",
        "Builds RESTful API layers",
        "Uses JAX-RS in production",
        "Spring REST is used throughout"
    })
    void extractsQualifiedRestEvidence(String description) {
        assertThat(technologiesOf(description)).contains("REST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "HTTP/REST is used across services",
        "Networking protocols (TCP/IP, DHCP, HTTP / REST)",
        "Experience with REST/JSON and Git",
        "Payloads use REST / JSON everywhere",
        "Integrating public APIs (REST)",
        "Exposes an API (REST) for partners",
        "Exposes an API ( REST ) for partners"
    })
    void extractsTechnicalRestShorthand(String description) {
        assertThat(technologiesOf(description)).contains("REST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Experience with REST is required",
        "Experience in REST is required",
        "Knowledge of REST is required",
        "Understanding of REST is required",
        "Familiarity with REST is required",
        "Proficiency in REST is required",
        "Skills with REST are required"
    })
    void extractsRestAfterATechnicalIntroducer(String description) {
        assertThat(technologiesOf(description)).contains("REST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Experience and understanding of OO, MVC, REST.",
        "Solid grasp of OOP, MVC, REST",
        "Java, Spring Boot, REST, SQL, PostgreSQL",
        "technologies: Java, REST, SQL",
        "stack: Java, REST, SQL"
    })
    void extractsRestAsATechnologyListItem(String description) {
        assertThat(technologiesOf(description)).contains("REST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Integration with the rest of the stack",
        "ready for integration with the rest of the stack",
        "Automation with Rest Assured",
        "Automation with RestAssured",
        "learn tools such as Selenium, Cucumber, BDD, Rest Assured and CI/CD",
        "Consumes the interest API daily",
        "Calls the forest API nightly",
        "Integrates a restaurant API",
        "leave the rest for later",
        "introduce you to the rest of the team",
        "rest of your responsibilities are listed below",
        "experience with the rest of the stack"
    })
    void rejectsUnqualifiedRestEvidence(String description) {
        assertThat(technologiesOf(description)).doesNotContain("REST");
    }

    @Test
    void extractsRestWhenDirtyAndGenuineEvidenceBothOccur() {
        assertThat(technologiesOf("Automation with Rest Assured over the rest of the stack, "
                + "plus a genuine REST API layer")).contains("REST");
        assertThat(technologiesOf("Rest Assured for testing; services expose REST APIs"))
                .contains("REST");
    }

    /** Verbatim contexts from the nine production rows the audit surfaced. */
    @ParameterizedTest
    @ValueSource(strings = {
        "Experience and understanding of OO, MVC, REST.",
        "Networking protocols and technologies (TCP/IP, DHCP, HTTP/REST)",
        "Experience with REST/JSON and Git",
        "Experience in exposing and integrating public APIs (REST)",
        "Java, Spring Boot, REST, SQL, PostgreSQL"
    })
    void keepsGenuineProductionRestEvidence(String description) {
        assertThat(technologiesOf(description)).contains("REST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "learn tools such as Selenium, Cucumber, BDD, Rest Assured and CI/CD",
        "ready for integration with the rest of the stack"
    })
    void dropsDirtyProductionRestEvidence(String description) {
        assertThat(technologiesOf(description)).doesNotContain("REST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Golang experience preferred",
        "Understands goroutine scheduling",
        "Tuning goroutines at scale",
        "Hiring a Go developer",
        "Hiring a Go engineer",
        "Go programming is a plus",
        "The Go language is used here",
        "Uses Go modules",
        "Knows Go routines",
        "Python, Go, Java",
        "languages: Go, Rust",
        "programming languages: Go, Rust",
        "technologies: Go, Rust",
        "tech stack: Go, Rust",
        "stack: Go, Rust",
        "experience with Go and Rust",
        "experience in Go and Rust",
        "proficient with Go, Rust",
        "proficient in Go, Rust",
        "written in Go, mostly",
        "skills with Go and Rust",
        "skills in Go and Rust"
    })
    void extractsGoOnlyWithTechnicalContext(String description) {
        assertThat(technologiesOf(description)).contains("Go");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ready to go, Java developers should apply",
        "go and Java developers will review it",
        "Python developers go and Java developers follow",
        "ready to go / Java experience preferred",
        "go, Java is installed separately",
        "experience with Java and go to market",
        "please go ahead and apply",
        "willing to go the extra mile",
        "supports go-live weekends",
        "experience with go-to-market strategy"
    })
    void rejectsOrdinaryEnglishGo(String description) {
        assertThat(technologiesOf(description)).doesNotContain("Go");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Experience with Spring Boot 3",
        "Experience with SpringBoot microservices",
        "We use Spring-Boot in production"
    })
    void canonicalisesSpringBootAliases(String description) {
        assertThat(technologiesOf(description)).contains("Spring Boot");
    }

    @Test
    void extractsJvmAsItsOwnCanonicalTerm() {
        assertThat(technologiesOf("Services run on the JVM"))
                .contains("JVM").doesNotContain("Java");
    }

    @Test
    void jvmIsNotAProgrammingLanguage() {
        var requirements = extractor.extract(
                jobWithDescription("Software Engineer", "Runs on the JVM, written in Go, mostly"));

        assertThat(requirements.technologies()).contains("JVM", "Go");
        assertThat(requirements.programmingLanguages()).contains("Go").doesNotContain("JVM");
    }

    /** Deliberate deferral: the Postgres alias waits for the scoring redesign, not this patch. */
    @Test
    void extractsExactPostgresqlButNotThePostgresAlias() {
        assertThat(technologiesOf("We run PostgreSQL 16 here")).contains("PostgreSQL");
        assertThat(technologiesOf("Data stored in Postgres")).doesNotContain("PostgreSQL");
    }

    @Test
    void keepsExistingWordBoundaryBehaviour() {
        assertThat(technologiesOf("Strong JavaScript skills only"))
                .contains("JavaScript").doesNotContain("Java");
        assertThat(technologiesOf("Core Java expertise")).contains("Java");
        assertThat(technologiesOf("Pipelines in GitHub Actions"))
                .contains("GitHub Actions").doesNotContain("Git");
        assertThat(technologiesOf("Version control with Git")).contains("Git");
        assertThat(technologiesOf("MySQL and NoSQL stores")).doesNotContain("SQL");
        assertThat(technologiesOf("Strong SQL skills")).contains("SQL");
        assertThat(technologiesOf("Pipelines use CI/CD")).contains("CI/CD");
        assertThat(technologiesOf("Pipelines use CI CD")).contains("CI/CD");
        assertThat(technologiesOf("Pipelines use CICD")).contains("CI/CD");
        assertThat(technologiesOf("Backends in C# and C++")).contains("C#", "C++");
    }

    /** The terms this patch does not touch must keep matching exactly as before. */
    @Test
    void keepsEveryUntouchedTechnologyWorking() {
        assertThat(technologiesOf("Java, Spring MVC, Spring Security, SQL, PostgreSQL, JPA, "
                + "Hibernate, Maven, JUnit, Mockito, React, React Native, TypeScript, "
                + "JavaScript, HTML, CSS, Git, GitHub Actions, CI/CD, Docker, Kubernetes, "
                + "Python, Kotlin, C#, C++, AWS, Azure and GCP"))
                .contains("Java", "Spring MVC", "Spring Security", "SQL", "PostgreSQL", "JPA",
                        "Hibernate", "Maven", "JUnit", "Mockito", "React", "React Native",
                        "TypeScript", "JavaScript", "HTML", "CSS", "Git", "GitHub Actions",
                        "CI/CD", "Docker", "Kubernetes", "Python", "Kotlin", "C#", "C++",
                        "AWS", "Azure", "GCP");
    }

    /**
     * Record equality is order-sensitive and now decides rescore plan membership, so extraction
     * order must be stable rather than salted per JVM run.
     */
    @Test
    void extractsTechnologiesInAStableDeclarationOrder() {
        String description = "Kubernetes, Docker, React, PostgreSQL and Java";

        assertThat(technologiesOf(description))
                .containsExactly("Java", "PostgreSQL", "React", "Docker", "Kubernetes");
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
