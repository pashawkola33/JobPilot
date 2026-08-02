package com.jobpilot.jobs.service;

import com.jobpilot.common.Utf16;

import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.EarlyCareerEligibility;
import com.jobpilot.jobs.domain.ExperienceRequirement;
import com.jobpilot.jobs.domain.RawCareerData;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.SeniorityLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Conservative hard gate for vacancies intended for students, graduates, and junior candidates. */
@Service
public class EarlyCareerEligibilityService {
    private static final Pattern NUMBER_OF_YEARS = Pattern.compile(
            "(?<first>\\d+(?:\\.\\d+)?)\\s*(?:(?<range>-|–|—|to)\\s*"
                    + "(?<second>\\d+(?:\\.\\d+)?))?\\s*(?<plus>\\+)?\\s*years?\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NO_EXPERIENCE = Pattern.compile(
            "\\b(?:no|without)\\s+(?:(?:previous|prior|professional|commercial|full[- ]time|work)\\s+)*"
                    + "experience\\s+(?:is\\s+)?(?:required|necessary|needed)\\b"
                    + "|\\bno\\s+prior\\s+(?:professional\\s+)?experience\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern GRADUATE_SIGNAL = Pattern.compile(
            "\\b(?:new|recent)\\s+graduates?\\s+(?:are\\s+)?(?:welcome|encouraged|accepted)\\b"
                    + "|\\bsuitable\\s+for\\s+(?:students?\\s+or\\s+)?recent\\s+graduates?\\b"
                    + "|\\bgraduate\\s+(?:programme|program|scheme|role|opportunit(?:y|ies))\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STUDENT_SIGNAL = Pattern.compile(
            "\\b(?:students?\\s+(?:are\\s+)?(?:welcome|accepted|encouraged)|suitable\\s+for\\s+students?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OPTIONAL_EXPERIENCE = Pattern.compile(
            "\\b(?:preferred|ideally|desirable|nice[- ]to[- ]have|bonus|advantageous|optional)\\b"
                    + "|\\b(?:preferred|helpful)\\s+but\\s+not\\s+required\\b"
                    + "|\\bnot\\s+(?:strictly\\s+)?required\\b|\\ba\\s+plus\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NON_COMMERCIAL_CONTEXT = Pattern.compile(
            "\\b(?:university|academic|personal|side|school)\\s+projects?\\b"
                    + "|\\b(?:coursework|github\\s+portfolio|internships?|basic\\s+familiarity)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMMERCIAL_CONTEXT = Pattern.compile(
            "\\b(?:professional|commercial|full[- ]time|industry|paid)\\s+experience\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public EarlyCareerDecision evaluate(RawJob raw) {
        RawCareerData structured = raw.careerData();
        SeniorityLevel providerLevel = levelOf(structured.providerSeniority());
        SeniorityLevel employmentLevel = levelOf(raw.employmentType());
        SeniorityLevel titleLevel = levelOfTitle(raw.title());
        String description = safe(raw.description());
        SeniorityLevel descriptionLevel = levelOfDescription(description);

        ExperienceRequirement structuredExperience = structuredExperience(structured);
        ExperienceRequirement descriptionExperience = parseExperience(description, null);
        ExperienceRequirement selectedExperience = structuredExperience.known()
                ? structuredExperience : descriptionExperience;

        String responsibilityBlocker = responsibilityBlocker(description);
        if (responsibilityBlocker != null) {
            return decision(SeniorityLevel.LEADERSHIP, selectedExperience,
                    EarlyCareerEligibility.INELIGIBLE, responsibilityBlocker);
        }

        if (descriptionExperience.mandatory() && hardExperienceBlocker(descriptionExperience)) {
            return decision(bestLevel(providerLevel, employmentLevel, titleLevel), descriptionExperience,
                    EarlyCareerEligibility.INELIGIBLE, experienceRejection(descriptionExperience));
        }
        if (structuredExperience.mandatory() && hardExperienceBlocker(structuredExperience)) {
            return decision(bestLevel(providerLevel, employmentLevel, titleLevel), structuredExperience,
                    EarlyCareerEligibility.INELIGIBLE, experienceRejection(structuredExperience));
        }

        SeniorityLevel rejectedLevel = rejectedLevel(providerLevel, employmentLevel, titleLevel);
        if (rejectedLevel != null) {
            return decision(rejectedLevel, selectedExperience, EarlyCareerEligibility.INELIGIBLE,
                    rejectedLevel == SeniorityLevel.LEADERSHIP ? "Leadership title" : rejectedLevelName(rejectedLevel));
        }

        if (descriptionExperience.mandatory() && threeYearReview(descriptionExperience)) {
            return decision(bestLevel(providerLevel, employmentLevel, titleLevel), descriptionExperience,
                    EarlyCareerEligibility.UNKNOWN,
                    "Exactly 3 years of mandatory experience requires manual review");
        }
        if (structuredExperience.mandatory() && threeYearReview(structuredExperience)) {
            return decision(bestLevel(providerLevel, employmentLevel, titleLevel), structuredExperience,
                    EarlyCareerEligibility.UNKNOWN,
                    "Exactly 3 years of mandatory experience requires manual review");
        }

        SeniorityLevel explicitLevel = bestLevel(providerLevel, employmentLevel, titleLevel);
        if (isEarlyCareer(explicitLevel)) {
            return decision(explicitLevel, selectedExperience, EarlyCareerEligibility.ELIGIBLE,
                    eligibleLevelReason(explicitLevel, selectedExperience));
        }

        if (isEarlyCareer(descriptionLevel)) {
            return decision(descriptionLevel, selectedExperience, EarlyCareerEligibility.ELIGIBLE,
                    eligibleLevelReason(descriptionLevel, selectedExperience));
        }

        if (descriptionExperience.mandatory() && withinEarlyCareer(descriptionExperience)) {
            return decision(SeniorityLevel.ENTRY_LEVEL, descriptionExperience,
                    EarlyCareerEligibility.ELIGIBLE, experienceEligibility(descriptionExperience));
        }
        if (structuredExperience.mandatory() && withinEarlyCareer(structuredExperience)) {
            return decision(SeniorityLevel.ENTRY_LEVEL, structuredExperience,
                    EarlyCareerEligibility.ELIGIBLE, experienceEligibility(structuredExperience));
        }
        if (NO_EXPERIENCE.matcher(description).find()) {
            return decision(SeniorityLevel.ENTRY_LEVEL,
                    new ExperienceRequirement(0.0, 0.0, false, matchedText(NO_EXPERIENCE, description)),
                    EarlyCareerEligibility.ELIGIBLE, "No previous experience required");
        }
        if (GRADUATE_SIGNAL.matcher(description).find()) {
            return decision(SeniorityLevel.GRADUATE, selectedExperience,
                    EarlyCareerEligibility.ELIGIBLE, "Suitable for new or recent graduates");
        }
        if (STUDENT_SIGNAL.matcher(description).find()) {
            return decision(SeniorityLevel.WORKING_STUDENT, selectedExperience,
                    EarlyCareerEligibility.ELIGIBLE, "Students are explicitly accepted");
        }

        return decision(SeniorityLevel.UNKNOWN, selectedExperience, EarlyCareerEligibility.UNKNOWN,
                "No seniority or experience requirement could be determined");
    }

    private ExperienceRequirement structuredExperience(RawCareerData data) {
        if (!data.hasExperienceRequirement()) return ExperienceRequirement.unknown();
        if (data.minimumYears() != null || data.maximumYears() != null) {
            boolean mandatory = data.mandatory() == null || data.mandatory();
            return new ExperienceRequirement(data.minimumYears(), data.maximumYears(), mandatory,
                    blankToNull(data.rawExperienceText()));
        }
        return parseExperience(data.rawExperienceText(), data.mandatory());
    }

    private ExperienceRequirement parseExperience(String text, Boolean mandatoryOverride) {
        if (text == null || text.isBlank()) return ExperienceRequirement.unknown();
        List<ExperienceRequirement> candidates = new ArrayList<>();
        Matcher matcher = NUMBER_OF_YEARS.matcher(text);
        while (matcher.find()) {
            int start = clauseStart(text, matcher.start());
            int end = clauseEnd(text, matcher.end());
            String context = Utf16.slice(text, start, end);
            String normalized = context.toLowerCase(Locale.ROOT);
            if (!normalized.contains("experience") && !normalized.contains("professional background")) continue;
            if (NON_COMMERCIAL_CONTEXT.matcher(context).find()
                    && !COMMERCIAL_CONTEXT.matcher(context).find()) continue;

            double first = Double.parseDouble(matcher.group("first"));
            Double second = matcher.group("second") == null
                    ? null : Double.parseDouble(matcher.group("second"));
            String before = text.substring(Math.max(0, matcher.start() - 28), matcher.start())
                    .toLowerCase(Locale.ROOT);
            boolean optional = OPTIONAL_EXPERIENCE.matcher(context).find();
            boolean mandatory = mandatoryOverride != null ? mandatoryOverride : !optional;
            Double minimum;
            Double maximum;
            if (second != null) {
                minimum = Math.min(first, second);
                maximum = Math.max(first, second);
            } else if (before.matches("(?s).*\\b(?:up to|maximum(?: of)?|no more than)\\s*$")) {
                minimum = 0.0;
                maximum = first;
            } else if (before.matches("(?s).*\\bmore than\\s*$")) {
                minimum = first + 0.001;
                maximum = null;
            } else if (matcher.group("plus") != null
                    || before.matches("(?s).*\\b(?:at least|minimum(?: of)?|min\\.?)\\s*$")) {
                minimum = first;
                maximum = null;
            } else {
                minimum = first;
                maximum = first;
            }
            candidates.add(new ExperienceRequirement(minimum, maximum, mandatory, bounded(context.strip())));
        }
        if (candidates.isEmpty()) {
            Matcher noExperience = NO_EXPERIENCE.matcher(text);
            return noExperience.find()
                    ? new ExperienceRequirement(0.0, 0.0, false, bounded(noExperience.group()))
                    : ExperienceRequirement.unknown();
        }
        return candidates.stream().max(Comparator
                        .comparing(ExperienceRequirement::mandatory)
                        .thenComparingDouble(this::upperBound)
                        .thenComparingDouble(value -> value.minimumYears() == null ? -1 : value.minimumYears()))
                .orElseThrow();
    }

    private String responsibilityBlocker(String description) {
        String text = description.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (matches(text, "\\b(?:people management|line management|direct reports?)\\b"
                + "|\\b(?:manage|managing|lead|leading)\\s+(?:an? |the )?(?:engineering |software |development )?team\\b"
                + "|\\bhiring\\s+and\\s+(?:conducting\\s+)?performance reviews?\\b"
                + "|\\b(?:hiring|performance reviews?)\\s+(?:and|for|of)\\s+.*\\bteam\\b")) {
            return "Requires people or team management";
        }
        if (matches(text, "\\b(?:ownership of|own)\\s+(?:an? |the )?(?:team|department)\\b")) {
            return "Requires ownership of a team or department";
        }
        if (matches(text, "\\btechnical leadership\\b|\\bprovide\\s+technical\\s+(?:leadership|direction)\\b")) {
            return "Requires technical leadership";
        }
        if (matches(text, "\\barchitecture ownership\\b|\\bown(?:ing)?\\s+(?:the )?(?:system |technical |platform )?architecture\\b"
                + "|\\bdefine\\s+(?:the )?(?:technical |system )?architecture\\b|\\barchitectural direction\\b")) {
            return "Requires architecture ownership";
        }
        if (matches(text, "\\b(?:primary|main|key) responsibility.{0,80}\\bmentor"
                + "|\\bresponsible for (?:coaching (?:and|or) )?mentoring\\b")) {
            return "Mentoring is a primary responsibility";
        }
        if (matches(text, "\\bsenior stakeholder management\\b"
                + "|\\b(?:manage|managing|management of).{0,35}\\b(?:senior|executive|c-level) stakeholders?\\b")) {
            return "Requires senior stakeholder management";
        }
        return null;
    }

    private SeniorityLevel levelOf(String value) {
        if (value == null || value.isBlank()) return SeniorityLevel.UNKNOWN;
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
                .replaceAll("\\s+", " ").strip();
        text = text.replaceAll("\\blead generation\\b", "sales generation");
        if (matches(text, "\\b(?:staff|principal|architect|manager|head|director|vice president|vp|executive|chief|leadership)\\b"
                + "|\\b(?:technical|engineering|software|team|product|project|program|data|qa) lead\\b"
                + "|\\blead (?:engineer|developer|architect|designer|scientist|team|manager)\\b"
                + "|\\bteam leader\\b|^lead\\b|\\blead$")) {
            return SeniorityLevel.LEADERSHIP;
        }
        if (matches(text, "\\bsenior\\b|\\bsr\\.?\\b")) return SeniorityLevel.SENIOR;
        if (matches(text, "\\bmid(?:dle)? level\\b|\\bintermediate\\b|\\bmid\\b")) {
            return SeniorityLevel.MID_LEVEL;
        }
        if (matches(text, "\\bworking student\\b|\\bwerkstudent\\b")) {
            return SeniorityLevel.WORKING_STUDENT;
        }
        if (matches(text, "\\bintern(?:ship)?\\b")) return SeniorityLevel.INTERNSHIP;
        if (matches(text, "\\b(?:trainee|traineeship|apprentice|apprenticeship)\\b")) {
            return SeniorityLevel.TRAINEE;
        }
        if (matches(text, "\\bgraduate\\b|\\bnew grad\\b")) return SeniorityLevel.GRADUATE;
        if (matches(text, "\\bentry level\\b|\\bearly career\\b")) return SeniorityLevel.ENTRY_LEVEL;
        if (matches(text, "\\bjunior\\b|\\bjr\\.?\\b")) return SeniorityLevel.JUNIOR;
        return SeniorityLevel.UNKNOWN;
    }

    private SeniorityLevel levelOfTitle(String title) {
        String text = safe(title).toLowerCase(Locale.ROOT);
        if (text.matches(".*\\bsenior student\\b.*")) {
            text = text.replaceAll("\\bsenior student\\b", "student");
        }
        SeniorityLevel level = levelOf(text);
        if (level == SeniorityLevel.UNKNOWN
                && matches(text, "\\b(?:engineer|developer|designer|scientist)\\s+(?:ii|2)\\b")) {
            return SeniorityLevel.MID_LEVEL;
        }
        return level;
    }

    private SeniorityLevel levelOfDescription(String description) {
        String text = description.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (matches(text, "\\bthis (?:is|will be) an? internship\\b"
                + "|\\binternship (?:role|position|program|programme|opportunity)\\b"
                + "|\\bintern (?:role|position|program|programme|opportunity)\\b")) {
            return SeniorityLevel.INTERNSHIP;
        }
        if (matches(text, "\\bworking student (?:role|position|opportunity)\\b")) {
            return SeniorityLevel.WORKING_STUDENT;
        }
        if (matches(text, "\\b(?:trainee|traineeship|apprentice|apprenticeship) "
                + "(?:role|position|program|programme|opportunity)\\b")) {
            return SeniorityLevel.TRAINEE;
        }
        if (matches(text, "\\bgraduate (?:role|position|program|programme|scheme|opportunity)\\b")) {
            return SeniorityLevel.GRADUATE;
        }
        if (matches(text, "\\b(?:entry[- ]level|early career) (?:role|position|opportunity)\\b")) {
            return SeniorityLevel.ENTRY_LEVEL;
        }
        if (matches(text, "\\bthis (?:is|will be) an? junior (?:role|position)\\b"
                + "|\\bjunior (?:role|position|opportunity)\\b")) {
            return SeniorityLevel.JUNIOR;
        }
        return SeniorityLevel.UNKNOWN;
    }

    private SeniorityLevel rejectedLevel(SeniorityLevel provider, SeniorityLevel employment,
                                          SeniorityLevel title) {
        if (isRejected(provider)) return provider;
        if (isRejected(employment)) return employment;
        if (isRejected(title)) return title;
        return null;
    }

    private SeniorityLevel bestLevel(SeniorityLevel provider, SeniorityLevel employment,
                                     SeniorityLevel title) {
        if (provider != SeniorityLevel.UNKNOWN) return provider;
        if (employment != SeniorityLevel.UNKNOWN) return employment;
        return title;
    }

    private boolean isEarlyCareer(SeniorityLevel level) {
        return switch (level) {
            case INTERNSHIP, TRAINEE, WORKING_STUDENT, GRADUATE, ENTRY_LEVEL, JUNIOR -> true;
            default -> false;
        };
    }

    private boolean isRejected(SeniorityLevel level) {
        return level == SeniorityLevel.MID_LEVEL || level == SeniorityLevel.SENIOR
                || level == SeniorityLevel.LEADERSHIP;
    }

    private boolean exceedsEarlyCareer(ExperienceRequirement requirement) {
        return requirement.minimumYears() != null && requirement.minimumYears() > 2.0
                || requirement.maximumYears() != null && requirement.maximumYears() > 2.0;
    }

    private boolean hardExperienceBlocker(ExperienceRequirement requirement) {
        return requirement.minimumYears() != null && requirement.minimumYears() >= 4.0
                || requirement.maximumYears() != null && requirement.maximumYears() >= 4.0;
    }

    private boolean threeYearReview(ExperienceRequirement requirement) {
        if (!requirement.known()) return false;
        double upper = upperBound(requirement);
        double lower = requirement.minimumYears() == null ? upper : requirement.minimumYears();
        return lower >= 3.0 && upper < 4.0;
    }

    private boolean withinEarlyCareer(ExperienceRequirement requirement) {
        if (!requirement.known()) return false;
        if (exceedsEarlyCareer(requirement)) return false;
        Double threshold = requirement.maximumYears() != null
                ? requirement.maximumYears() : requirement.minimumYears();
        return threshold != null && threshold <= 2.0;
    }

    private double upperBound(ExperienceRequirement requirement) {
        if (requirement.maximumYears() != null) return requirement.maximumYears();
        if (requirement.minimumYears() != null) return requirement.minimumYears();
        return -1;
    }

    private String experienceRejection(ExperienceRequirement requirement) {
        double threshold = upperBound(requirement);
        if (requirement.minimumYears() != null && requirement.maximumYears() != null
                && !requirement.minimumYears().equals(requirement.maximumYears())) {
            return "Mandatory experience range includes 4 or more years";
        }
        if (threshold >= 4 && Math.rint(threshold) == threshold) {
            return "Requires at least " + (int) threshold + " years of professional experience";
        }
        return "Mandatory experience requirement is at least 4 years";
    }

    private String experienceEligibility(ExperienceRequirement requirement) {
        if (requirement.minimumYears() != null && requirement.maximumYears() != null) {
            return "Entry-level role requiring " + display(requirement.minimumYears()) + "–"
                    + display(requirement.maximumYears()) + " years of experience";
        }
        return "Experience requirement does not exceed 2 years";
    }

    private String eligibleLevelReason(SeniorityLevel level, ExperienceRequirement experience) {
        if (experience.known() && !experience.mandatory() && exceedsEarlyCareer(experience)) {
            return displayLevel(level) + " role; additional experience is preferred but not required";
        }
        if (experience.known() && withinEarlyCareer(experience)) {
            return displayLevel(level) + " role requiring no more than 2 years of experience";
        }
        return switch (level) {
            case GRADUATE -> "Graduate programme suitable for recent graduates";
            case ENTRY_LEVEL -> "Explicit entry-level role";
            default -> displayLevel(level) + " role";
        };
    }

    private String rejectedLevelName(SeniorityLevel level) {
        return switch (level) {
            case MID_LEVEL -> "Mid-level title";
            case SENIOR -> "Senior title";
            default -> "Leadership title";
        };
    }

    private String displayLevel(SeniorityLevel level) {
        return switch (level) {
            case INTERNSHIP -> "Internship";
            case TRAINEE -> "Trainee or apprenticeship";
            case WORKING_STUDENT -> "Working-student";
            case GRADUATE -> "Graduate";
            case ENTRY_LEVEL -> "Entry-level";
            case JUNIOR -> "Junior";
            default -> "Early-career";
        };
    }

    private String display(double value) {
        return Math.rint(value) == value ? Integer.toString((int) value) : Double.toString(value);
    }

    private EarlyCareerDecision decision(SeniorityLevel level, ExperienceRequirement experience,
                                         EarlyCareerEligibility eligibility, String reason) {
        return new EarlyCareerDecision(level, experience, eligibility, reason);
    }

    private boolean matches(String text, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    private int clauseStart(String text, int position) {
        int boundary = -1;
        for (char delimiter : new char[]{'.', ';', '\n', '\r', '•', '·', '|'}) {
            boundary = Math.max(boundary, text.lastIndexOf(delimiter, position - 1));
        }
        return Math.max(boundary + 1, position - 160);
    }

    private int clauseEnd(String text, int position) {
        int boundary = text.length();
        for (char delimiter : new char[]{'.', ';', '\n', '\r', '•', '·', '|'}) {
            int candidate = text.indexOf(delimiter, position);
            if (candidate >= 0) boundary = Math.min(boundary, candidate);
        }
        return Math.min(boundary, position + 160);
    }

    private String matchedText(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? bounded(matcher.group()) : null;
    }

    private String bounded(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return Utf16.truncate(normalized, 500);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
