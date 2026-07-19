package com.jobpilot.resume.validation;

import com.jobpilot.resume.application.CandidateDocumentFacts;
import com.jobpilot.resume.application.JobDocumentFacts;
import com.jobpilot.resume.application.ResumeDraftBuilder;
import com.jobpilot.resume.application.ResumeDraftPlan;
import com.jobpilot.resume.domain.ResumeDocumentModel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ResumeTruthValidator {
    private static final int MAX_NAME = 200;
    private static final int MAX_TITLE = 120;
    private static final int MAX_SUMMARY = 700;
    private static final Set<String> FORBIDDEN_TITLE_WORDS = Set.of(
            "senior", "lead", "expert", "professional", "experienced", "principal", "staff");

    public void validatePlan(ResumeDraftPlan plan, CandidateDocumentFacts facts,
                             JobDocumentFacts job) {
        if (plan == null || plan.titleStyle() == null
                || !ResumeDraftBuilder.allowedTitleStyles(job).contains(plan.titleStyle())) {
            fail("The selected title style is not supported by the vacancy and truth model");
        }
        requireUnique(plan.skillKeys(), "skill");
        requireUnique(plan.projectKeys(), "project");
        requireUnique(plan.bulletKeys(), "project bullet");
        requireUnique(plan.languageKeys(), "language");

        Map<String, CandidateDocumentFacts.SkillFact> skills = index(facts.skills(),
                CandidateDocumentFacts.SkillFact::stableKey);
        int activeUniqueSkills = (int) facts.skills().stream()
                .filter(CandidateDocumentFacts.SkillFact::active)
                .map(value -> value.normalizedName().toLowerCase(Locale.ROOT)).distinct().count();
        int minimumSkills = Math.min(ResumeDraftBuilder.MIN_SKILLS, activeUniqueSkills);
        if (plan.skillKeys().size() < minimumSkills
                || plan.skillKeys().size() > ResumeDraftBuilder.MAX_SKILLS) {
            fail("The skill selection is outside its deterministic bounds");
        }
        Set<String> normalizedSkills = new HashSet<>();
        for (String key : plan.skillKeys()) {
            CandidateDocumentFacts.SkillFact fact = skills.get(key);
            if (fact == null || !fact.active()) fail("An unverified or inactive skill was selected");
            if (!normalizedSkills.add(fact.normalizedName().strip().toLowerCase(Locale.ROOT))) {
                fail("Duplicate normalized skills were selected");
            }
        }

        Map<String, CandidateDocumentFacts.ProjectFact> projects = index(facts.projects(),
                CandidateDocumentFacts.ProjectFact::stableKey);
        if (plan.projectKeys().isEmpty()
                || plan.projectKeys().size() > ResumeDraftBuilder.MAX_PROJECTS) {
            fail("The project selection is outside its deterministic bounds");
        }
        for (String key : plan.projectKeys()) {
            CandidateDocumentFacts.ProjectFact fact = projects.get(key);
            if (fact == null || !fact.active()) fail("An unverified or inactive project was selected");
        }
        Map<String, CandidateDocumentFacts.BulletFact> bullets = facts.projects().stream()
                .flatMap(value -> value.bullets().stream())
                .collect(Collectors.toMap(CandidateDocumentFacts.BulletFact::qualifiedKey,
                        Function.identity()));
        Map<String, Integer> bulletCounts = new HashMap<>();
        for (String key : plan.bulletKeys()) {
            CandidateDocumentFacts.BulletFact bullet = bullets.get(key);
            if (bullet == null || !bullet.active() || !plan.projectKeys().contains(bullet.projectKey())) {
                fail("An unverified, inactive, or unrelated project bullet was selected");
            }
            bulletCounts.merge(bullet.projectKey(), 1, Integer::sum);
        }
        for (String projectKey : plan.projectKeys()) {
            CandidateDocumentFacts.ProjectFact project = projects.get(projectKey);
            int activeBullets = (int) project.bullets().stream()
                    .filter(CandidateDocumentFacts.BulletFact::active).count();
            int minimum = Math.min(ResumeDraftBuilder.MIN_BULLETS_PER_PROJECT, activeBullets);
            int count = bulletCounts.getOrDefault(projectKey, 0);
            if (count < minimum || count > ResumeDraftBuilder.MAX_BULLETS_PER_PROJECT) {
                fail("A project bullet selection is outside its deterministic bounds");
            }
        }

        Map<String, CandidateDocumentFacts.LanguageFact> languages = index(facts.languages(),
                CandidateDocumentFacts.LanguageFact::stableKey);
        int allowedLanguages = (int) facts.languages().stream()
                .filter(value -> value.active() && value.allowedInCv()).count();
        int minimumLanguages = Math.min(2, allowedLanguages);
        if (plan.languageKeys().size() < minimumLanguages
                || plan.languageKeys().size() > ResumeDraftBuilder.MAX_LANGUAGES) {
            fail("The language selection is outside its deterministic bounds");
        }
        for (String key : plan.languageKeys()) {
            CandidateDocumentFacts.LanguageFact fact = languages.get(key);
            if (fact == null || !fact.active() || !fact.allowedInCv()) {
                fail("An inactive or CV-disallowed language was selected");
            }
        }
    }

    public void validate(ResumeDocumentModel model, CandidateDocumentFacts facts,
                         JobDocumentFacts job) {
        require(model != null, "Resume model is required");
        require(equal(model.fullName(), facts.fullName()) && bounded(model.fullName(), MAX_NAME),
                "Candidate name is not an exact verified profile fact");
        require(bounded(model.selectedRoleTitle(), MAX_TITLE), "Resume title is invalid");
        String normalizedTitle = model.selectedRoleTitle().toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_TITLE_WORDS) {
            require(!normalizedTitle.matches(".*\\b" + forbidden + "\\b.*"),
                    "Resume title strengthens the verified seniority");
        }
        Set<String> allowedTitles = ResumeDraftBuilder.allowedTitleStyles(job).stream()
                .map(ResumeDraftBuilder::title).collect(Collectors.toSet());
        require(allowedTitles.contains(model.selectedRoleTitle()),
                "Resume title is not a bounded supported title");

        ResumeDocumentModel.Education education = model.education();
        require(education != null && equal(education.institution(), facts.educationInstitution())
                        && equal(education.degree(), facts.degree())
                        && education.startYear() == facts.studyStartYear()
                        && java.util.Objects.equals(education.endYear(), facts.studyEndYear())
                        && education.currentStudent() == facts.currentStudent(),
                "Education does not exactly match the verified profile");

        Map<Long, CandidateDocumentFacts.SkillFact> skills = facts.skills().stream()
                .collect(Collectors.toMap(CandidateDocumentFacts.SkillFact::id, Function.identity()));
        Set<String> normalizedSkills = new HashSet<>();
        for (ResumeDocumentModel.Skill selected : model.skills()) {
            CandidateDocumentFacts.SkillFact fact = skills.get(selected.factId());
            require(fact != null && fact.active() && equal(selected.stableKey(), fact.stableKey())
                            && equal(selected.normalizedName(), fact.normalizedName())
                            && equal(selected.displayName(), fact.displayName()),
                    "A resume skill is not an exact active verified fact");
            require(normalizedSkills.add(fact.normalizedName().toLowerCase(Locale.ROOT)),
                    "Duplicate normalized resume skills are not allowed");
        }

        Map<Long, CandidateDocumentFacts.LanguageFact> languages = facts.languages().stream()
                .collect(Collectors.toMap(CandidateDocumentFacts.LanguageFact::id, Function.identity()));
        for (ResumeDocumentModel.Language selected : model.languages()) {
            CandidateDocumentFacts.LanguageFact fact = languages.get(selected.factId());
            require(fact != null && fact.active() && fact.allowedInCv()
                            && equal(selected.stableKey(), fact.stableKey())
                            && equal(selected.language(), fact.language())
                            && equal(selected.verifiedLevel(), fact.verifiedLevel()),
                    "A resume language or level is not an exact CV-allowed verified fact");
        }

        Map<Long, CandidateDocumentFacts.ProjectFact> projects = facts.projects().stream()
                .collect(Collectors.toMap(CandidateDocumentFacts.ProjectFact::id, Function.identity()));
        Set<String> expectedClaims = new HashSet<>();
        for (ResumeDocumentModel.Project selected : model.projects()) {
            CandidateDocumentFacts.ProjectFact fact = projects.get(selected.factId());
            require(fact != null && fact.active() && equal(selected.stableKey(), fact.stableKey())
                            && equal(selected.name(), fact.name())
                            && equal(selected.description(), fact.description())
                            && equal(selected.projectType(), fact.projectType())
                            && selected.technologies().equals(fact.technologies()),
                    "A resume project is not an exact active verified fact");
            Map<Long, CandidateDocumentFacts.BulletFact> bullets = fact.bullets().stream()
                    .collect(Collectors.toMap(CandidateDocumentFacts.BulletFact::id,
                            Function.identity()));
            for (ResumeDocumentModel.Bullet selectedBullet : selected.bullets()) {
                CandidateDocumentFacts.BulletFact bullet = bullets.get(selectedBullet.factId());
                require(bullet != null && bullet.active()
                                && equal(selectedBullet.stableKey(), bullet.stableKey())
                                && equal(selectedBullet.verifiedText(), bullet.verifiedText()),
                        "A project bullet is not exact verified text");
                expectedClaims.add("Can discuss: " + bullet.verifiedText() + "\u0000"
                        + fact.stableKey() + ":" + bullet.stableKey());
            }
        }
        require(model.projects().size() >= 1
                        && model.projects().size() <= ResumeDraftBuilder.MAX_PROJECTS,
                "Resume project count is invalid");
        require(model.professionalSummary().length() <= MAX_SUMMARY
                        && model.professionalSummary().equals(
                        ResumeDraftBuilder.expectedSummary(facts, model.skills(), model.projects())),
                "Resume summary contains unsupported wording");
        require(!model.professionalSummary().toLowerCase(Locale.ROOT)
                        .matches(".*\\b(professional|senior|expert|experienced)\\b.*"),
                "Resume summary strengthens verified experience");

        for (ResumeDocumentModel.InterviewClaim claim : model.interviewClaims()) {
            require(claim.factKeys().size() == 1
                            && expectedClaims.contains(claim.statement() + "\u0000" + claim.factKeys().getFirst()),
                    "An interview claim is not derived from an exact selected bullet");
        }
        require(model.interviewClaims().size() <= 6, "Too many interview claims");
        require(model.changeSummary().size() == 3
                        && model.changeSummary().stream().allMatch(value -> bounded(value, 300)),
                "Change summary is invalid");
        require(model.templateVersion() != null
                        && model.templateVersion().matches("[A-Za-z0-9._-]{1,80}"),
                "Resume template version is invalid");
    }

    private <T> Map<String, T> index(List<T> values, Function<T, String> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private void requireUnique(List<String> values, String label) {
        if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())
                || values.size() != new HashSet<>(values).size()) {
            fail("The " + label + " selection contains invalid or duplicate keys");
        }
    }

    private boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean equal(String first, String second) {
        return java.util.Objects.equals(first, second);
    }

    private void require(boolean condition, String message) {
        if (!condition) fail(message);
    }

    private void fail(String message) {
        throw new ResumeTruthValidationException(message);
    }
}
