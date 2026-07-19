package com.jobpilot.resume.application;

import com.jobpilot.llm.domain.JobAnalysisData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class ResumeTruthTestFixtures {
    private ResumeTruthTestFixtures() {
    }

    static CandidateDocumentFacts candidate() {
        List<CandidateDocumentFacts.SkillFact> skills = new ArrayList<>();
        String[] values = {"Java 21", "Spring Boot", "REST APIs", "PostgreSQL", "SQL",
                "JUnit 5", "Maven", "Git", "Docker", "Theoretical understanding of Agile"};
        for (int index = 0; index < values.length; index++) {
            String key = values[index].toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("-$", "");
            skills.add(new CandidateDocumentFacts.SkillFact(index + 1, key,
                    values[index].toLowerCase(java.util.Locale.ROOT), values[index],
                    index == 9 ? "ADDITIONAL" : "BACKEND",
                    index == 9 ? "Verified theoretical understanding only" : "Verified project skill",
                    true, index));
        }
        skills.add(new CandidateDocumentFacts.SkillFact(50, "inactive-kubernetes", "kubernetes",
                "Kubernetes", "BACKEND", "Inactive test fact", false, 50));
        List<CandidateDocumentFacts.LanguageFact> languages = List.of(
                new CandidateDocumentFacts.LanguageFact(101, "english", "English", "FLUENT", true, true, 0),
                new CandidateDocumentFacts.LanguageFact(102, "german", "German", "INTERMEDIATE", true, true, 1),
                new CandidateDocumentFacts.LanguageFact(103, "hidden", "Hidden", "NATIVE", false, true, 2));
        List<CandidateDocumentFacts.BulletFact> bullets = List.of(
                new CandidateDocumentFacts.BulletFact(201, 301, "project-one", "spring-rest",
                        "Uses a Spring Boot REST backend.", List.of("Spring Boot", "REST"), true, 0),
                new CandidateDocumentFacts.BulletFact(202, 301, "project-one", "postgres",
                        "Uses PostgreSQL entities and repositories.", List.of("PostgreSQL"), true, 1),
                new CandidateDocumentFacts.BulletFact(203, 301, "project-one", "tests",
                        "Includes tests using JUnit 5.", List.of("JUnit"), true, 2),
                new CandidateDocumentFacts.BulletFact(204, 301, "project-one", "inactive-metric",
                        "Increased revenue by 90 percent.", List.of("revenue"), false, 3));
        CandidateDocumentFacts.ProjectFact project = new CandidateDocumentFacts.ProjectFact(
                301, "project-one", "Verified Student API", "Personal backend project",
                "PERSONAL_PROJECT", List.of("Java 21", "Spring Boot", "PostgreSQL"),
                true, 0, bullets);
        return new CandidateDocumentFacts(1, 1, "a".repeat(64), "Synthetic Student",
                "Bucharest, Romania", "Synthetic University", "BSc in Economic Informatics",
                2025, null, true, false, BigDecimal.ZERO, skills, languages, List.of(project));
    }

    static JobDocumentFacts job(String description) {
        JobAnalysisData analysis = new JobAnalysisData("Java backend internship",
                List.of("Java", "Spring Boot", "SQL"), List.of("Docker"),
                List.of("Build REST services"), null, null, null,
                "Bucharest", null, List.of(), List.of("Commercial experience is not verified"),
                List.of(), List.of(), 80, true);
        return new JobDocumentFacts(1, "Java Backend Intern", "Synthetic Company",
                "Bucharest", description, "b".repeat(64), 2, "c".repeat(64), analysis);
    }
}
