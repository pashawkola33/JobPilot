package com.jobpilot.resume.application;

import com.jobpilot.llm.domain.CandidateStrength;
import com.jobpilot.resume.domain.DocumentContactBlock;
import com.jobpilot.resume.domain.ResumeDocumentModel;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ResumeDraftBuilder {
    public static final int MIN_SKILLS = 8;
    public static final int MAX_SKILLS = 16;
    public static final int MAX_PROJECTS = 3;
    public static final int MIN_BULLETS_PER_PROJECT = 2;
    public static final int MAX_BULLETS_PER_PROJECT = 4;
    public static final int MAX_LANGUAGES = 5;

    public ResumeDraftPlan deterministicPlan(CandidateDocumentFacts facts, JobDocumentFacts job) {
        String corpus = corpus(job);
        Set<String> strengthKeys = job.analysis() == null ? Set.of()
                : job.analysis().candidateStrengths().stream()
                .map(CandidateStrength::factKey).collect(Collectors.toSet());

        List<CandidateDocumentFacts.SkillFact> rankedSkills = uniqueSkills(facts.skills()).stream()
                .sorted(Comparator.<CandidateDocumentFacts.SkillFact>comparingInt(
                                value -> score(value.stableKey() + " " + value.normalizedName()
                                        + " " + value.displayName(), corpus)
                                        + (strengthKeys.contains(value.stableKey()) ? 80 : 0))
                        .reversed().thenComparingInt(CandidateDocumentFacts.SkillFact::displayOrder)
                        .thenComparing(CandidateDocumentFacts.SkillFact::stableKey))
                .limit(Math.min(12, MAX_SKILLS)).toList();

        List<CandidateDocumentFacts.ProjectFact> rankedProjects = facts.projects().stream()
                .filter(CandidateDocumentFacts.ProjectFact::active)
                .sorted(Comparator.<CandidateDocumentFacts.ProjectFact>comparingInt(
                                value -> projectScore(value, corpus))
                        .reversed().thenComparingInt(CandidateDocumentFacts.ProjectFact::displayOrder)
                        .thenComparing(CandidateDocumentFacts.ProjectFact::stableKey))
                .limit(Math.min(2, MAX_PROJECTS)).toList();

        List<String> bulletKeys = new ArrayList<>();
        for (CandidateDocumentFacts.ProjectFact project : rankedProjects) {
            project.bullets().stream().filter(CandidateDocumentFacts.BulletFact::active)
                    .sorted(Comparator.<CandidateDocumentFacts.BulletFact>comparingInt(
                                    value -> score(value.verifiedText() + " "
                                            + String.join(" ", value.keywords()), corpus))
                            .reversed().thenComparingInt(CandidateDocumentFacts.BulletFact::displayOrder)
                            .thenComparing(CandidateDocumentFacts.BulletFact::stableKey))
                    .limit(3).map(CandidateDocumentFacts.BulletFact::qualifiedKey)
                    .forEach(bulletKeys::add);
        }

        List<String> languages = facts.languages().stream()
                .filter(value -> value.active() && value.allowedInCv())
                .sorted(Comparator.comparingInt(CandidateDocumentFacts.LanguageFact::displayOrder)
                        .thenComparing(CandidateDocumentFacts.LanguageFact::stableKey))
                .limit(MAX_LANGUAGES).map(CandidateDocumentFacts.LanguageFact::stableKey).toList();

        return new ResumeDraftPlan(defaultTitleStyle(job),
                rankedSkills.stream().map(CandidateDocumentFacts.SkillFact::stableKey).toList(),
                rankedProjects.stream().map(CandidateDocumentFacts.ProjectFact::stableKey).toList(),
                bulletKeys, languages);
    }

    public ResumeDocumentModel build(CandidateDocumentFacts facts, JobDocumentFacts job,
                                     DocumentContactBlock contact, ResumeDraftPlan plan,
                                     String templateVersion) {
        Map<String, CandidateDocumentFacts.SkillFact> skillsByKey = index(facts.skills(),
                CandidateDocumentFacts.SkillFact::stableKey);
        Map<String, CandidateDocumentFacts.LanguageFact> languagesByKey = index(facts.languages(),
                CandidateDocumentFacts.LanguageFact::stableKey);
        Map<String, CandidateDocumentFacts.ProjectFact> projectsByKey = index(facts.projects(),
                CandidateDocumentFacts.ProjectFact::stableKey);
        Map<String, CandidateDocumentFacts.BulletFact> bulletsByKey = facts.projects().stream()
                .flatMap(project -> project.bullets().stream())
                .collect(Collectors.toMap(CandidateDocumentFacts.BulletFact::qualifiedKey,
                        Function.identity()));

        List<ResumeDocumentModel.Skill> selectedSkills = plan.skillKeys().stream()
                .map(skillsByKey::get).filter(java.util.Objects::nonNull)
                .map(value -> new ResumeDocumentModel.Skill(value.id(), value.stableKey(),
                        value.normalizedName(), value.displayName())).toList();
        Set<String> requestedBullets = new LinkedHashSet<>(plan.bulletKeys());
        List<ResumeDocumentModel.Project> selectedProjects = plan.projectKeys().stream()
                .map(projectsByKey::get).filter(java.util.Objects::nonNull)
                .map(project -> new ResumeDocumentModel.Project(project.id(), project.stableKey(),
                        project.name(), project.description(), project.projectType(),
                        project.technologies(), requestedBullets.stream().map(bulletsByKey::get)
                        .filter(java.util.Objects::nonNull)
                        .filter(bullet -> bullet.projectId() == project.id())
                        .map(bullet -> new ResumeDocumentModel.Bullet(bullet.id(),
                                bullet.stableKey(), bullet.verifiedText())).toList()))
                .toList();
        List<ResumeDocumentModel.Language> selectedLanguages = plan.languageKeys().stream()
                .map(languagesByKey::get).filter(java.util.Objects::nonNull)
                .map(value -> new ResumeDocumentModel.Language(value.id(), value.stableKey(),
                        value.language(), value.verifiedLevel())).toList();

        ResumeDocumentModel.Education education = new ResumeDocumentModel.Education(
                facts.educationInstitution(), facts.degree(), facts.studyStartYear(),
                facts.studyEndYear(), facts.currentStudent());
        String title = title(plan.titleStyle());
        String summary = expectedSummary(facts, selectedSkills, selectedProjects);
        List<String> changes = List.of(
                "Selected " + selectedSkills.size() + " verified skills for relevance to the vacancy.",
                "Prioritized " + selectedProjects.size() + " verified personal or academic projects.",
                "Kept verified language levels and omitted unsupported work experience.");
        List<ResumeDocumentModel.InterviewClaim> claims = selectedProjects.stream()
                .flatMap(project -> project.bullets().stream().map(bullet ->
                        new ResumeDocumentModel.InterviewClaim(
                                "Can discuss: " + bullet.verifiedText(),
                                List.of(project.stableKey() + ":" + bullet.stableKey()))))
                .limit(6).toList();
        return new ResumeDocumentModel(facts.fullName(), contact, title, summary, education,
                selectedSkills, selectedLanguages, selectedProjects, changes, claims,
                templateVersion);
    }

    public static String expectedSummary(CandidateDocumentFacts facts,
                                         List<ResumeDocumentModel.Skill> skills,
                                         List<ResumeDocumentModel.Project> projects) {
        String study = facts.degree().replaceFirst("(?i)^BSc\\s+in\\s+", "").strip();
        if (study.isBlank()) study = facts.degree();
        List<String> practical = skills.stream()
                .filter(value -> !value.displayName().toLowerCase(Locale.ROOT).contains("theoretical"))
                .limit(4).map(ResumeDocumentModel.Skill::displayName).toList();
        String skillText = practical.isEmpty() ? "verified software-development skills"
                : joinNatural(practical);
        String projectText = projects.stream().map(ResumeDocumentModel.Project::name)
                .limit(2).collect(Collectors.joining(" and "));
        String first = facts.currentStudent()
                ? study + " student at " + facts.educationInstitution()
                : facts.degree() + " graduate from " + facts.educationInstitution();
        return first + " with personal or academic project work using " + skillText + ". "
                + "Built " + projectText + " from verified project requirements and features.";
    }

    public static String title(ResumeDraftPlan.TitleStyle style) {
        return switch (style) {
            case BACKEND_STUDENT -> "Backend-Focused Student Developer";
            case FULL_STACK_STUDENT -> "Full-Stack Student Developer";
            case SOFTWARE_STUDENT -> "Software Development Student";
        };
    }

    public static Set<ResumeDraftPlan.TitleStyle> allowedTitleStyles(JobDocumentFacts job) {
        String terms = normalize(job.title() + " " + job.description());
        Set<ResumeDraftPlan.TitleStyle> styles = new LinkedHashSet<>();
        if (terms.contains("backend") || terms.contains("java") || terms.contains("spring")) {
            styles.add(ResumeDraftPlan.TitleStyle.BACKEND_STUDENT);
        }
        if (terms.contains("full stack") || terms.contains("fullstack")
                || terms.contains("frontend") && terms.contains("backend")) {
            styles.add(ResumeDraftPlan.TitleStyle.FULL_STACK_STUDENT);
        }
        styles.add(ResumeDraftPlan.TitleStyle.SOFTWARE_STUDENT);
        return Set.copyOf(styles);
    }

    private ResumeDraftPlan.TitleStyle defaultTitleStyle(JobDocumentFacts job) {
        Set<ResumeDraftPlan.TitleStyle> allowed = allowedTitleStyles(job);
        if (allowed.contains(ResumeDraftPlan.TitleStyle.FULL_STACK_STUDENT)) {
            return ResumeDraftPlan.TitleStyle.FULL_STACK_STUDENT;
        }
        if (allowed.contains(ResumeDraftPlan.TitleStyle.BACKEND_STUDENT)) {
            return ResumeDraftPlan.TitleStyle.BACKEND_STUDENT;
        }
        return ResumeDraftPlan.TitleStyle.SOFTWARE_STUDENT;
    }

    private List<CandidateDocumentFacts.SkillFact> uniqueSkills(
            List<CandidateDocumentFacts.SkillFact> values) {
        Map<String, CandidateDocumentFacts.SkillFact> unique = new LinkedHashMap<>();
        values.stream().filter(CandidateDocumentFacts.SkillFact::active)
                .sorted(Comparator.comparingInt(CandidateDocumentFacts.SkillFact::displayOrder)
                        .thenComparing(CandidateDocumentFacts.SkillFact::stableKey))
                .forEach(value -> unique.putIfAbsent(normalize(value.normalizedName()), value));
        return List.copyOf(unique.values());
    }

    private int projectScore(CandidateDocumentFacts.ProjectFact value, String corpus) {
        int result = score(value.name() + " " + value.description() + " "
                + String.join(" ", value.technologies()), corpus);
        for (CandidateDocumentFacts.BulletFact bullet : value.bullets()) {
            if (bullet.active()) result += score(bullet.verifiedText() + " "
                    + String.join(" ", bullet.keywords()), corpus) / 3;
        }
        return result;
    }

    private String corpus(JobDocumentFacts job) {
        StringBuilder value = new StringBuilder(job.title()).append(' ')
                .append(job.description()).append(' ');
        if (job.analysis() != null) {
            value.append(String.join(" ", job.analysis().mustHaveRequirements())).append(' ')
                    .append(String.join(" ", job.analysis().preferredRequirements())).append(' ')
                    .append(String.join(" ", job.analysis().responsibilities())).append(' ')
                    .append(String.join(" ", job.analysis().candidateGaps())).append(' ');
        }
        return normalize(value.toString());
    }

    private int score(String candidate, String normalizedCorpus) {
        String normalized = normalize(candidate);
        if (normalized.isBlank()) return 0;
        int result = normalizedCorpus.contains(normalized) ? 100 : 0;
        Set<String> tokens = new HashSet<>(List.of(normalized.split(" ")));
        for (String token : tokens) {
            if (token.length() >= 3 && normalizedCorpus.contains(token)) result += 8;
        }
        return result;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+#.]+", " ")
                .strip().replaceAll("\\s+", " ");
    }

    private static <T> Map<String, T> index(List<T> values, Function<T, String> key) {
        Map<String, T> result = new HashMap<>();
        for (T value : values) result.put(key.apply(value), value);
        return result;
    }

    private static String joinNatural(List<String> values) {
        if (values.size() == 1) return values.getFirst();
        return String.join(", ", values.subList(0, values.size() - 1))
                + " and " + values.getLast();
    }
}
