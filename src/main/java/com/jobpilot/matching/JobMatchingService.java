package com.jobpilot.matching;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class JobMatchingService {
    private static final Set<String> BACKEND = Set.of("java", "spring boot", "rest", "sql", "postgresql", "jpa", "maven", "junit");
    private static final Set<String> SUPPORTING = Set.of("react", "typescript", "javascript", "html", "css", "git", "ci/cd", "github actions");
    private final Clock clock;

    public JobMatchingService(Clock clock) {
        this.clock = clock;
    }

    public ScoreCard score(Job job, ExtractedRequirements r) {
        String text = (job.getTitle() + " " + job.getDescription() + " " + job.getLocation()).toLowerCase(Locale.ROOT);
        List<String> strengths = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        int formal = 0;
        if (r.internshipOrTrainee() || has(text, "student", "graduate", "entry-level", "junior")) formal += 8;
        if (has(text, "computer science", "informatics", "software", "technical degree", "related field")) formal += 5;
        else if (r.requiredEducation() == null) formal += 4;
        if (!r.finalYearMandatory()) formal += 6;
        if (romaniaEligible(job, r)) formal += 6;
        if (formal >= 19) strengths.add("Formal eligibility is compatible with a current student in Romania");

        int backendMatches = (int) r.technologies().stream().map(String::toLowerCase).filter(BACKEND::contains).distinct().count();
        int backend = Math.min(25, Math.round(25f * backendMatches / BACKEND.size()));
        if (backendMatches > 0) strengths.add("Matches " + backendMatches + " confirmed Java/backend technologies");

        int trainee = 0;
        if (r.internshipOrTrainee()) trainee += 9;
        if (!r.mentorshipSignals().isEmpty()) trainee += 6;
        else if (has(text, "graduate", "structured learning", "training")) trainee += 3;
        trainee = Math.min(15, trainee);
        if (trainee >= 9) strengths.add("Role has internship, trainee, or structured-learning signals");

        int supportingMatches = (int) r.technologies().stream().map(String::toLowerCase)
                .filter(SUPPORTING::contains).distinct().count();
        int supporting = Math.min(10, Math.round(10f * supportingMatches / 5f));
        if (supportingMatches > 0) strengths.add("Supporting frontend/tooling skills are relevant");

        int location = locationScore(job, r);
        if (location >= 8) strengths.add("Location and work format suit Bucharest or Romania");

        int experience = experienceScore(r.requiredExperienceYears(), r.internshipOrTrainee());
        if (experience >= 8) strengths.add("Commercial experience expectations are entry-level compatible");

        int freshness = freshness(job);
        if (freshness >= 4) strengths.add("Vacancy is recent and appears open");

        int penalties = 0;
        Double years = r.requiredExperienceYears();
        if (years != null && years >= 3) blockers.add("Mandatory 3+ years of experience");
        else if (years != null && years >= 2) {
            penalties += 35;
            risks.add("Mandatory 2+ years of commercial experience");
        }
        if (r.finalYearMandatory()) {
            penalties += 30;
            risks.add("Final-year student status is mandatory");
        }
        if ("SENIOR".equals(r.seniority()) || "MIDDLE".equals(r.seniority())) {
            blockers.add("Middle or senior seniority");
        }
        String languages = String.join(" ", r.spokenLanguages()).toLowerCase(Locale.ROOT);
        if (languages.contains("french") && mandatoryLanguage(languages, "french")) {
            penalties += 25;
            risks.add("French B2 or professional fluency is mandatory");
        }
        if (languages.contains("romanian") && mandatoryLanguage(languages, "romanian")) {
            penalties += 20;
            risks.add("Professional Romanian fluency is mandatory");
        }
        if (r.remoteEligibility() != null && r.remoteEligibility().toLowerCase(Locale.ROOT).contains("not eligible")) {
            blockers.add("Role cannot be performed from Romania");
        }
        if (has(text, "unpaid") && has(text, "full-time", "full time") && r.mentorshipSignals().isEmpty()) {
            penalties += 25;
            risks.add("Unpaid full-time role without structured training");
        }
        boolean expired = job.getDeadline() != null && job.getDeadline().isBefore(clock.instant())
                || has(text, "position closed", "no longer accepting applications");
        if (expired) blockers.add("Vacancy is closed or expired");

        int raw = formal + backend + trainee + supporting + location + experience + freshness - penalties;
        int total = blockers.isEmpty() ? Math.clamp(raw, 0, 100) : 0;
        boolean suitable = blockers.isEmpty();
        ScoreBand band = !suitable ? ScoreBand.UNSUITABLE : total >= 85 ? ScoreBand.EXCELLENT_MATCH
                : total >= 70 ? ScoreBand.GOOD_MATCH : total >= 55 ? ScoreBand.POSSIBLE_MATCH : ScoreBand.LOW_MATCH;
        return new ScoreCard(total, band, suitable, formal, backend, trainee, supporting, location,
                experience, freshness, penalties, List.copyOf(strengths), List.copyOf(risks), List.copyOf(blockers));
    }

    private int experienceScore(Double years, boolean trainee) {
        if (years == null) return trainee ? 10 : 7;
        if (years <= 0) return 10;
        if (years <= 1) return 8;
        if (years < 2) return 4;
        return 0;
    }

    private int freshness(Job job) {
        Instant published = job.getPublishedAt() == null ? job.getFirstSeenAt() : job.getPublishedAt();
        long age = Math.max(0, Duration.between(published, clock.instant()).toDays());
        if (job.getStatus() == JobStatus.EXPIRED || job.getDeadline() != null && job.getDeadline().isBefore(clock.instant())) return 0;
        return age <= 7 ? 5 : age <= 14 ? 4 : age <= 30 ? 2 : 1;
    }

    private int locationScore(Job job, ExtractedRequirements r) {
        String text = (String.valueOf(job.getLocation()) + " " + String.valueOf(r.remoteEligibility())).toLowerCase(Locale.ROOT);
        if (has(text, "bucharest", "bucurești")) return 10;
        if (text.contains("remote") && text.contains("romania")) return 10;
        if (text.contains("romania")) return 8;
        if (text.contains("remote") && !text.contains("unclear")) return 6;
        return 2;
    }

    private boolean romaniaEligible(Job job, ExtractedRequirements r) {
        String remote = String.valueOf(r.remoteEligibility()).toLowerCase(Locale.ROOT);
        String location = String.valueOf(job.getLocation()).toLowerCase(Locale.ROOT);
        return !remote.contains("not eligible") && (remote.contains("romania") || location.contains("romania")
                || location.contains("bucharest") || location.contains("bucurești"));
    }

    private boolean mandatoryLanguage(String allLanguages, String language) {
        int index = allLanguages.indexOf(language);
        String nearby = allLanguages.substring(index, Math.min(allLanguages.length(), index + 100));
        return has(nearby, "mandatory", "required", "fluent", "b2", "c1", "c2");
    }

    private boolean has(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
