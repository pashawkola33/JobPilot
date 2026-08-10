package com.jobpilot.extraction;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import java.util.ArrayList;
import java.util.Collections;
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
    /** Technical tokens REST is credibly listed beside; longer alternatives come first. */
    private static final String REST_TECH_TOKEN = "(?:OOP|OO|MVVM|MVC|MVP|SOAP|JSON|XML|YAML"
            + "|HTTPS|HTTP|gRPC|GraphQL|SOA|microservices|NoSQL|PostgreSQL|MySQL|MongoDB|SQL"
            + "|Redis|Kafka|RabbitMQ|Spring Boot|Spring|Hibernate|JPA|JDBC|Maven|Gradle|JUnit"
            + "|Mockito|Selenium|Cucumber|BDD|TDD|Docker|Kubernetes|CI/CD|Jenkins|Linux|AWS"
            + "|Azure|GCP|HTML|CSS|React|Angular|Node|Django|Flask|\\.NET|APIs|API|JavaScript"
            + "|TypeScript|Java|Kotlin|Scala|Python|C#|C\\+\\+|Git)";
    /** Never a bare space: that is what keeps "Rest Assured" out of every list form. */
    private static final String REST_LIST_DELIMITER = "(?:\\s*[,;/]\\s*|\\s+(?:and|or)\\s+)";
    private static final String REST_LIST_END = "(?=\\s*[.)\\];:]|\\s*$)";
    private static final String REST_INTRODUCER = "(?:experience\\s+(?:with|in)|knowledge\\s+of"
            + "|understanding\\s+of|familiarity\\s+with|proficiency\\s+(?:in|with)"
            + "|proficient\\s+(?:in|with)|skills?\\s+(?:in|with))";

    /**
     * Qualified REST. Bare "rest" is an ordinary English noun and "Rest Assured" is a test
     * library, so neither is API evidence on its own, but production showed the token also
     * carries genuine evidence as technical shorthand ("HTTP/REST", "REST/JSON", "APIs (REST)"),
     * directly after a technical introducer, and as an item in a delimited technology list
     * ("OO, MVC, REST", "Java, Spring Boot, REST, SQL").
     *
     * <p>The list forms require REST to be a <em>complete</em> item — a delimiter on both sides,
     * or a delimiter and the end of the list — and the delimiter never matches a bare space. That
     * is what keeps "Selenium, Cucumber, BDD, Rest Assured and CI/CD" negative: "Rest" there is
     * followed by a word, not by a delimiter. A posting carrying both dirty and genuine text
     * still matches, because the scan reaches the genuine occurrence.
     */
    private static final String QUALIFIED_REST = "(?:"
            + "RESTful"
            + "|REST[\\s-]?(?:API|service|endpoint|controller)s?"
            + "|JAX-?RS"
            + "|Spring\\s+REST"
            + "|HTTP\\s*/\\s*REST"
            + "|REST\\s*/\\s*JSON"
            + "|APIs?\\s*\\(\\s*REST\\s*\\)"
            + "|" + REST_INTRODUCER + "\\s+REST"
            + "|" + REST_TECH_TOKEN + REST_LIST_DELIMITER + "REST"
                  + REST_LIST_DELIMITER + REST_TECH_TOKEN
            + "|" + REST_TECH_TOKEN + REST_LIST_DELIMITER + REST_TECH_TOKEN
                  + REST_LIST_DELIMITER + "REST" + REST_LIST_END
            + "|REST" + REST_LIST_DELIMITER + REST_TECH_TOKEN
                  + REST_LIST_DELIMITER + REST_TECH_TOKEN
            + ")";

    private static final String LANGUAGE_TOKEN = "(?:Java|Python|Rust|Kotlin|Scala|Ruby|PHP"
            + "|C\\+\\+|C#|TypeScript|JavaScript|Elixir|Erlang|Swift|Haskell)";
    private static final String LANGUAGE_DELIMITER = "(?:\\s*[,/]\\s*|\\s+(?:and|or)\\s+)";
    private static final String TECHNICAL_INTRODUCER = "(?:programming\\s+languages?|languages?"
            + "|technologies|tech\\s+stack|stack|experience\\s+(?:with|in)"
            + "|proficient\\s+(?:with|in)|written\\s+in|skills\\s+(?:with|in))";
    /** Go has to end as a list item; without this "experience with Java and go to market" matches. */
    private static final String GO_LIST_TAIL =
            "(?=\\s*[,;./)\\]]|\\s+(?:and|or)\\s+" + LANGUAGE_TOKEN + "|\\s*$)";

    /**
     * "Go" is an ordinary English verb, so a bare token is never evidence. Either it is spelled
     * unambiguously, or it sits <em>between</em> two recognised languages in a delimited list, or
     * it follows an explicit technical introducer <em>and</em> terminates as a list item. Simple
     * two-item adjacency is deliberately rejected: "ready to go, Java developers should apply"
     * would otherwise qualify. Conservative false negatives are preferred here — Go carries no
     * scoring weight, while a false Go pollutes the evidence diagnostics depend on.
     */
    private static final String QUALIFIED_GO = "(?:Golang|goroutines?"
            + "|Go[\\s-]?(?:developer|engineer|programming|language|module|routine)s?"
            + "|" + LANGUAGE_TOKEN + LANGUAGE_DELIMITER + "Go" + LANGUAGE_DELIMITER + LANGUAGE_TOKEN
            + "|" + TECHNICAL_INTRODUCER + "\\s*:?\\s*(?:" + LANGUAGE_TOKEN + LANGUAGE_DELIMITER
            + "){0,3}Go" + GO_LIST_TAIL
            + ")";

    /**
     * Explicit Go notation in the job <em>title</em>. A title is short, curated and names the
     * stack directly, so two notations that are too weak to trust in prose are unambiguous
     * there: a parenthesised language tag ("Juju Software Engineer (Go)") and a slash-separated
     * language pair ("Software Engineer, Python / Go", "Software Engineer - Go / Python").
     * Production preview showed {@link #QUALIFIED_GO} alone dropping exactly those rows.
     *
     * <p>Deliberately limited to those two forms, and deliberately applied to the title only:
     * bare Go in a title is still the English verb ("Ready to Go Platform Engineer"), and the
     * body keeps the unchanged conservative rule. The unambiguous spellings — Golang, "Go
     * developer", "Go engineer" — already come through {@link #QUALIFIED_GO} wherever they sit.
     */
    private static final Pattern TITLE_GO = Pattern.compile("(?i)(?:"
            + "\\(\\s*Go\\s*\\)"
            + "|(?<![\\p{L}\\p{N}])" + LANGUAGE_TOKEN + "\\s*/\\s*Go(?![\\p{L}\\p{N}])"
            + "|(?<![\\p{L}\\p{N}])Go\\s*/\\s*" + LANGUAGE_TOKEN + "(?![\\p{L}\\p{N}])"
            + ")");

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
     * A level word attached to other people: colleagues, stakeholders, leadership, the person
     * the role reports to, the engineers the role mentors, or the people work is escalated to.
     * None of these describe the advertised vacancy.
     *
     * <p>Every alternative needs its own trigger context. There is deliberately no blanket rule
     * for "mentor", "senior engineers", "senior employees" or "senior level" on their own: those
     * appear in genuine role descriptions too ("mid-senior level openings"), and a broad
     * exclusion would silently downgrade real mid/senior vacancies.
     */
    private static final Pattern OTHER_PEOPLE_SENIORITY = Pattern.compile(
            "(?i)\\bsenior\\b\\s+(?:colleagues?|stakeholders?|leadership|management|leaders?|"
                    + "managers?|executives?|sponsors?|team\\s+members?|developers?\\s+who\\s+mentor)"
                    + "|\\b(?:report(?:s|ing)?)\\s+(?:in)?to\\s+(?:an?\\s+|the\\s+)?\\bsenior\\b"
                    + "|\\b(?:guidance|mentorship|mentoring|supervision|support|direction|oversight)"
                    + "\\s+(?:of|from|by)\\s+(?:an?\\s+|the\\s+|our\\s+)?\\bsenior\\b"
                    + "|\\b(?:work|working|collaborate|collaborating|liaise|liaising|partner|"
                    + "partnering|engage|engaging|paired?)\\s+(?:closely\\s+)?with\\s+"
                    + "(?:an?\\s+|the\\s+|our\\s+)?\\bsenior\\b"
                    // Comparative form. "less senior" is relative by construction: it can only
                    // describe somebody positioned below the advertised role, never the role.
                    // Covers the whole "mentor/mentors/mentoring/coach less senior X" family.
                    + "|\\bless\\s+senior\\b"
                    // Escalation recipients sit above the role, so naming them says nothing
                    // about the vacancy. Anchored on an escalation verb and kept inside one
                    // sentence, so an unrelated later clause cannot suppress a genuine level.
                    + "|\\bescalat\\w*\\b[^.!?]{0,80}?\\bto\\s+(?:an?\\s+|the\\s+|our\\s+)?\\bsenior\\b");

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
        // Seniority reads the title and the body separately, and Go additionally reads the
        // title on its own; everything else keeps using the combined text exactly as before.
        String titleText = String.valueOf(job.getTitle()).replace('\u00a0', ' ');
        String bodyText = String.valueOf(job.getDescription()).replace('\u00a0', ' ');
        List<String> technologies = extractTechnologies(text, titleText);
        List<String> programming = technologies.stream().filter(PROGRAMMING::contains).toList();
        List<String> spoken = spokenLanguages(text);
        List<String> mentoring = signals(lower, Map.of(
                "mentorship", "mentorship", "mentor", "mentor", "structured learning", "structured learning",
                "structured mentorship", "structured mentorship", "training program", "training program",
                "academy", "academy", "pair programming", "pair programming"));
        return new ExtractedRequirements(seniority(titleText, bodyText), trainee, experience(text),
                education(text), finalYear, technologies, programming, spoken,
                job.getLocation(), remoteEligibility(lower), mentoring, workAuthorization(text),
                salary(text), job.getDeadline(), "DETERMINISTIC");
    }

    /**
     * Insertion-ordered on purpose. {@link Map#copyOf} salts its iteration order per JVM run, so
     * the extracted technology list — and therefore {@link ExtractedRequirements} record equality —
     * used to differ between runs for identical text. Nothing depended on that while the rescore
     * plan compared scores only; requirement-aware plan membership does.
     */
    private static Map<String, Pattern> technologies() {
        Map<String, Pattern> map = new LinkedHashMap<>();
        for (String tech : List.of("Java", "JVM", "Spring Boot", "Spring MVC", "Spring Security",
                "REST", "SQL", "PostgreSQL", "JPA", "Hibernate", "Maven", "JUnit", "Mockito",
                "React", "React Native", "TypeScript", "JavaScript", "HTML", "CSS", "Git",
                "GitHub Actions", "CI/CD", "Docker", "Kubernetes", "Python", "Kotlin", "C#", "C++",
                "Go", "AWS", "Azure", "GCP")) {
            String expression = "(?i)(?<![\\p{L}\\p{N}])" + token(tech) + "(?![\\p{L}\\p{N}])";
            map.put(tech, Pattern.compile(expression));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * A literal token by default. Only terms carrying a spelling variant, or an English homograph
     * that would otherwise produce false evidence, override it. "PostgreSQL" stays literal: the
     * "Postgres" alias is a measured gap deliberately deferred to the scoring redesign, because
     * PostgreSQL is an equally weighted backend skill today and recovering it would promote
     * infrastructure postings this change is not meant to touch.
     */
    private static String token(String tech) {
        return switch (tech) {
            case "CI/CD" -> "CI(?:/|\\s*)CD";
            case "REST" -> QUALIFIED_REST;
            case "Go" -> QUALIFIED_GO;
            case "Spring Boot" -> "Spring[\\s-]?Boot";
            default -> Pattern.quote(tech);
        };
    }

    /**
     * Combined title+description evidence, plus the title-only Go recovery. The scan still walks
     * {@link #TECHNOLOGIES} in declaration order, so ordering stays stable and each term — Go
     * included, however many rules matched it — is emitted at most once.
     */
    private List<String> extractTechnologies(String text, String title) {
        return TECHNOLOGIES.entrySet().stream()
                .filter(e -> e.getValue().matcher(text).find()
                        || ("Go".equals(e.getKey()) && TITLE_GO.matcher(title).find()))
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
