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
    // Whole words only: "internal" or "international" must not count as an internship.
    private static final Pattern TRAINEE_SIGNALS = Pattern.compile(
            "(?i)\\b(?:interns?|internships?|trainees?|traineeships?|apprentices?|apprenticeships?|academy|graduate program(?:me)?s?)\\b");
    private static final Pattern INTERNSHIP_SENIORITY = Pattern.compile(
            "(?i)\\b(?:interns?|internships?|trainees?|traineeships?|apprentices?|apprenticeships?|academy)\\b");

    // Word-bounded so "seniority", "seniors" and other incidental substrings never match.
    private static final Pattern SENIOR_SENIORITY = Pattern.compile(
            "(?i)\\b(?:senior|staff engineer|lead developer|principal)\\b");
    private static final Pattern MIDDLE_SENIORITY = Pattern.compile(
            "(?i)\\b(?:mid[- ]level|middle developer|medior)\\b");
    private static final Pattern JUNIOR_SENIORITY = Pattern.compile(
            "(?i)\\b(?:junior|entry[- ]level|graduate)\\b");

    /**
     * A level word attached to other people: colleagues, stakeholders, leadership, or the
     * person the role reports to. None of these describe the advertised vacancy.
     */
    private static final Pattern OTHER_PEOPLE_SENIORITY = Pattern.compile(
            "(?i)\\bsenior\\b\\s+(?:colleagues?|stakeholders?|leadership|management|leaders?|"
                    + "managers?|executives?|sponsors?|team\\s+members?|developers?\\s+who\\s+mentor)"
                    + "|\\b(?:report(?:s|ing)?)\\s+(?:in)?to\\s+(?:an?\\s+|the\\s+)?\\bsenior\\b"
                    + "|\\b(?:guidance|mentorship|mentoring|supervision|support|direction|oversight)"
                    + "\\s+(?:of|from|by)\\s+(?:an?\\s+|the\\s+|our\\s+)?\\bsenior\\b"
                    + "|\\b(?:work|working|collaborate|collaborating|liaise|liaising|partner|"
                    + "partnering|engage|engaging|paired?)\\s+(?:closely\\s+)?with\\s+"
                    + "(?:an?\\s+|the\\s+|our\\s+)?\\bsenior\\b");

    /** A level word qualifying a training programme rather than the role being filled. */
    private static final Pattern PROGRAMME_AUDIENCE_LEVEL = Pattern.compile(
            "(?i)\\b(?:mid[- ]level|medior|senior|junior|entry[- ]level|graduate)\\b"
                    + "(?:\\s+\\w+){0,2}\\s+\\b(?:accelerator|programme|program|bootcamp|academy|"
                    + "course|track|cohort|curriculum|pathway)\\b");

    /**
     * One posting advertising several levels at once. Nothing reliable can be inferred, and
     * a wrong senior call zeroes the score, so such a body is treated as carrying no signal.
     */
    private static final Pattern MIXED_LEVEL_POSTING = Pattern.compile(
            "(?i)\\b(?:senior|mid[- ]level)\\b[^.!?]{0,80}?\\b(?:junior|entry[- ]level|graduate)\\b"
                    + "|\\b(?:junior|entry[- ]level|graduate)\\b[^.!?]{0,80}?\\b(?:senior|mid[- ]level)\\b");

    public ExtractedRequirements extract(Job job) {
        String text = (job.getTitle() + "\n" + job.getDescription()).replace('\u00a0', ' ');
        String lower = text.toLowerCase(Locale.ROOT);
        boolean trainee = TRAINEE_SIGNALS.matcher(text).find();
        boolean finalYear = Pattern.compile("(?i)(must|only|required|currently)\\s+(?:be\\s+)?(?:a\\s+)?final[- ]year|final[- ]year\\s+(student\\s+)?required")
                .matcher(text).find();
        List<String> technologies = extractTechnologies(text);
        List<String> programming = technologies.stream().filter(PROGRAMMING::contains).toList();
        List<String> spoken = spokenLanguages(text);
        List<String> mentoring = signals(lower, Map.of(
                "mentorship", "mentorship", "mentor", "mentor", "structured learning", "structured learning",
                "structured mentorship", "structured mentorship", "training program", "training program",
                "academy", "academy", "pair programming", "pair programming"));
        // Seniority reads the title and the body separately; everything else keeps using
        // the combined text exactly as before.
        String titleText = String.valueOf(job.getTitle()).replace('\u00a0', ' ');
        String bodyText = String.valueOf(job.getDescription()).replace('\u00a0', ' ');
        return new ExtractedRequirements(seniority(titleText, bodyText), trainee, experience(text),
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
            String token = "CI/CD".equals(tech) ? "CI(?:/|\\s*)CD" : Pattern.quote(tech);
            String expression = "(?i)(?<![\\p{L}\\p{N}])" + token + "(?![\\p{L}\\p{N}])";
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

    /**
     * Seniority of <em>this vacancy</em>, judged from the role title first.
     *
     * <p>The title names the role; the body describes the team, the stakeholders, and
     * sometimes a training programme aimed at a different audience. Scanning both as one
     * haystack let an incidental phrase such as "guidance of senior colleagues" or
     * "mid-level accelerator programme" override an explicitly junior title, and the
     * matching service turns that into a hard blocker. So: an unambiguous title wins
     * outright, and only then is the body consulted, with the phrases that describe other
     * people or another audience removed first.
     *
     * <p>When the body advertises several levels at once it is treated as ambiguous and
     * yields {@code UNKNOWN} rather than a guess, because a false senior classification
     * costs the whole score.
     */
    private String seniority(String title, String body) {
        String fromTitle = classifySeniority(title);
        if (fromTitle != null) return fromTitle;
        if (MIXED_LEVEL_POSTING.matcher(body).find()) return "UNKNOWN";
        String fromBody = classifySeniority(stripIncidentalSeniority(body));
        return fromBody == null ? "UNKNOWN" : fromBody;
    }

    /** Returns null when the text carries no seniority signal at all. */
    private String classifySeniority(String text) {
        if (SENIOR_SENIORITY.matcher(text).find()) return "SENIOR";
        if (MIDDLE_SENIORITY.matcher(text).find()) return "MIDDLE";
        if (INTERNSHIP_SENIORITY.matcher(text).find()) return "INTERNSHIP";
        if (JUNIOR_SENIORITY.matcher(text).find()) return "JUNIOR";
        return null;
    }

    /**
     * Blanks out level words that qualify somebody else, or a training programme, rather
     * than the advertised role. Replaced with a space so surrounding matches still work.
     */
    private String stripIncidentalSeniority(String body) {
        String cleaned = OTHER_PEOPLE_SENIORITY.matcher(body).replaceAll(" ");
        return PROGRAMME_AUDIENCE_LEVEL.matcher(cleaned).replaceAll(" ");
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
