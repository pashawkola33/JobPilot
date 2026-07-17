package com.jobpilot.extraction;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DeterministicRequirementExtractor {
    private static final Pattern YEARS = Pattern.compile(
            "(?i)(?:minimum|min\\.?|at least|requires?|with)?\\s*(\\d+(?:\\.\\d+)?)\\s*\\+?\\s*(?:years?|yrs?)(?:\\s+of)?(?:\\s+(?:commercial|professional|industry))?\\s+experience");
    private static final Map<String, Pattern> TECHNOLOGIES = technologies();
    private static final List<String> PROGRAMMING = List.of("Java", "TypeScript", "JavaScript", "Python", "Kotlin", "C#", "C++", "Go");

    public ExtractedRequirements extract(Job job) {
        String text = (job.getTitle() + "\n" + job.getDescription()).replace('\u00a0', ' ');
        String lower = text.toLowerCase(Locale.ROOT);
        boolean trainee = containsAny(lower, "intern", "trainee", "apprentice", "academy", "graduate program");
        boolean finalYear = Pattern.compile("(?i)(must|only|required|currently)\\s+(?:be\\s+)?(?:a\\s+)?final[- ]year|final[- ]year\\s+(student\\s+)?required")
                .matcher(text).find();
        List<String> technologies = extractTechnologies(text);
        List<String> programming = technologies.stream().filter(PROGRAMMING::contains).toList();
        List<String> spoken = spokenLanguages(text);
        List<String> mentoring = signals(lower, Map.of(
                "mentorship", "mentorship", "mentor", "mentor", "structured learning", "structured learning",
                "training program", "training program", "academy", "academy", "pair programming", "pair programming"));
        return new ExtractedRequirements(seniority(lower), trainee, experience(text),
                education(text), finalYear, technologies, programming, spoken,
                job.getLocation(), remoteEligibility(lower), mentoring, workAuthorization(text),
                salary(text), job.getDeadline(), "DETERMINISTIC");
    }

    private static Map<String, Pattern> technologies() {
        Map<String, Pattern> map = new LinkedHashMap<>();
        for (String tech : List.of("Java", "Spring Boot", "Spring MVC", "Spring Security", "REST", "SQL",
                "PostgreSQL", "JPA", "Hibernate", "Maven", "JUnit", "Mockito", "React", "React Native",
                "TypeScript", "JavaScript", "HTML", "CSS", "Git", "GitHub Actions", "CI/CD", "Docker",
                "Kubernetes", "Python", "Kotlin", "C#", "C++", "Go", "AWS", "Azure", "GCP")) {
            String expression = "(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(tech)
                    .replace("CI/CD", "CI(?:/|\\s*)CD") + "(?![\\p{L}\\p{N}])";
            map.put(tech, Pattern.compile(expression));
        }
        return Map.copyOf(map);
    }

    private List<String> extractTechnologies(String text) {
        return TECHNOLOGIES.entrySet().stream().filter(e -> e.getValue().matcher(text).find())
                .map(Map.Entry::getKey).toList();
    }

    private Double experience(String text) {
        Matcher matcher = YEARS.matcher(text);
        double maximum = -1;
        while (matcher.find()) maximum = Math.max(maximum, Double.parseDouble(matcher.group(1)));
        return maximum < 0 ? null : maximum;
    }

    private String seniority(String lower) {
        if (containsAny(lower, "senior", "staff engineer", "lead developer", "principal")) return "SENIOR";
        if (containsAny(lower, "mid-level", "middle developer", "medior")) return "MIDDLE";
        if (containsAny(lower, "intern", "trainee", "apprentice", "academy")) return "INTERNSHIP";
        if (containsAny(lower, "junior", "entry-level", "entry level", "graduate")) return "JUNIOR";
        return "UNKNOWN";
    }

    private String education(String text) {
        Matcher matcher = Pattern.compile("(?i)(bachelor.{0,100}|university student.{0,100}|degree in.{0,100})")
                .matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private List<String> spokenLanguages(String text) {
        List<String> result = new ArrayList<>();
        for (String language : List.of("English", "French", "Romanian", "German", "Ukrainian", "Russian")) {
            Matcher matcher = Pattern.compile("(?i)" + language + ".{0,45}(mandatory|required|fluen|B[12]|C[12])|"
                    + "(mandatory|required|fluen|B[12]|C[12]).{0,45}" + language).matcher(text);
            if (matcher.find()) result.add(language + " (" + matcher.group().strip() + ")");
        }
        return List.copyOf(result);
    }

    private String remoteEligibility(String lower) {
        if (containsAny(lower, "must be based in france", "us only", "united states only", "cannot work from romania")) {
            return "Romania not eligible";
        }
        if (containsAny(lower, "remote romania", "remote from romania", "based in romania", "romania remote")) {
            return "Remote from Romania allowed";
        }
        if (containsAny(lower, "bucharest", "bucurești", "romania")) return "Romania eligible";
        return lower.contains("remote") ? "Remote eligibility unclear" : null;
    }

    private String workAuthorization(String text) {
        Matcher matcher = Pattern.compile("(?i)(work authori[sz]ation.{0,100}|right to work.{0,100}|visa sponsorship.{0,100})")
                .matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private String salary(String text) {
        Matcher matcher = Pattern.compile("(?i)(?:€|EUR|RON|\\$)\\s?[\\d,.]+(?:\\s?[-–]\\s?(?:€|EUR|RON|\\$)?\\s?[\\d,.]+)?(?:\\s*/\\s*(?:year|month|hour))?")
                .matcher(text);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private List<String> signals(String lower, Map<String, String> patterns) {
        return patterns.entrySet().stream().filter(e -> lower.contains(e.getKey())).map(Map.Entry::getValue)
                .distinct().toList();
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
