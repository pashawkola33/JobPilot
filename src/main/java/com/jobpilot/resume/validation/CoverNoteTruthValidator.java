package com.jobpilot.resume.validation;

import com.jobpilot.resume.application.CandidateDocumentFacts;
import com.jobpilot.resume.application.CoverNoteDraftBuilder;
import com.jobpilot.resume.application.CoverNoteDraftPlan;
import com.jobpilot.resume.application.JobDocumentFacts;
import com.jobpilot.resume.domain.CoverNoteDocumentModel;
import com.jobpilot.resume.domain.ResumeDocumentModel;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CoverNoteTruthValidator {
    public void validatePlan(CoverNoteDraftPlan plan, CandidateDocumentFacts facts,
                             JobDocumentFacts job, ResumeDocumentModel resume) {
        require(plan != null, "Cover-note draft plan is required");
        requireUnique(plan.candidateFactKeys(), "candidate fact");
        requireUnique(plan.vacancyEvidenceKeys(), "vacancy evidence");
        Set<String> allowed = allowedCandidateKeys(facts, resume);
        require(allowed.containsAll(plan.candidateFactKeys()),
                "Cover-note plan referenced a fact not selected in the resume");
        require(plan.candidateFactKeys().contains("profile:" + facts.profileVersion()),
                "Cover-note plan must reference the verified profile");
        require(plan.candidateFactKeys().stream().filter(value -> value.startsWith("skill:")).count() >= 3,
                "Cover-note plan needs bounded verified skill support");
        require(plan.candidateFactKeys().stream().anyMatch(value -> value.startsWith("project:")),
                "Cover-note plan needs a verified project");
        require(plan.candidateFactKeys().stream().anyMatch(value -> value.startsWith("bullet:")),
                "Cover-note plan needs an exact verified project bullet");
        Set<String> evidence = new HashSet<>(List.of("job:title", "job:company"));
        if (!job.analysis().mustHaveRequirements().isEmpty()) evidence.add("analysis:must-have:0");
        if (!job.analysis().candidateGaps().isEmpty()) evidence.add("analysis:gap:0");
        require(evidence.containsAll(plan.vacancyEvidenceKeys())
                        && plan.vacancyEvidenceKeys().contains("job:title")
                        && plan.vacancyEvidenceKeys().contains("job:company"),
                "Cover-note plan referenced unsupported vacancy evidence");
    }

    public void validate(CoverNoteDocumentModel model, CandidateDocumentFacts facts,
                         JobDocumentFacts job, ResumeDocumentModel resume) {
        require(model != null && model.candidateName().equals(facts.fullName()),
                "Cover-note candidate name is unsupported");
        require(model.roleTitle().equals(bounded(job.title(), 200))
                        && model.company().equals(bounded(job.company(), 200)),
                "Cover-note role or company is unsupported");
        require(model.salutation().equals("Dear Hiring Team,")
                        && model.closing().equals("Sincerely,"),
                "Cover-note salutation or closing is not the neutral template");
        require(model.paragraphs().size() == 4, "Cover note must contain four bounded paragraphs");
        require(model.paragraphs().stream().allMatch(value -> value != null
                        && !value.isBlank() && value.length() <= 1_500
                        && value.chars().noneMatch(Character::isISOControl)),
                "Cover-note paragraph is outside its bounds");
        Set<String> allowed = allowedCandidateKeys(facts, resume);
        require(allowed.containsAll(model.referencedCandidateFactKeys()),
                "Cover note references an unsupported candidate fact");

        List<ResumeDocumentModel.Skill> selectedSkills = resume.skills().stream()
                .filter(value -> model.referencedCandidateFactKeys()
                        .contains("skill:" + value.stableKey())).limit(5).toList();
        List<ResumeDocumentModel.Project> selectedProjects = resume.projects().stream()
                .filter(value -> model.referencedCandidateFactKeys()
                        .contains("project:" + value.stableKey())).limit(2).toList();
        require(model.paragraphs().equals(CoverNoteDraftBuilder.expectedParagraphs(
                        facts, job, selectedSkills, selectedProjects,
                        model.referencedVacancyEvidence())),
                "Cover-note prose is not the validated canonical template");
        int words = model.plainText().strip().split("\\s+").length;
        require(words >= 180 && words <= 350, "Cover note must be approximately 180–350 words");
        String normalized = model.plainText().toLowerCase(Locale.ROOT);
        require(!normalized.contains("perfect match") && !normalized.contains("application was submitted")
                        && !normalized.contains("years of professional experience")
                        && !normalized.contains("bypass"),
                "Cover note contains prohibited or exaggerated language");
        require(model.templateVersion() != null
                        && model.templateVersion().matches("[A-Za-z0-9._-]{1,80}"),
                "Cover-note template version is invalid");
    }

    private Set<String> allowedCandidateKeys(CandidateDocumentFacts facts,
                                             ResumeDocumentModel resume) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("profile:" + facts.profileVersion());
        resume.skills().forEach(value -> keys.add("skill:" + value.stableKey()));
        resume.languages().forEach(value -> keys.add("language:" + value.stableKey()));
        resume.projects().forEach(project -> {
            keys.add("project:" + project.stableKey());
            project.bullets().forEach(bullet -> keys.add(
                    "bullet:" + project.stableKey() + ":" + bullet.stableKey()));
        });
        return Set.copyOf(keys);
    }

    private void requireUnique(List<String> values, String label) {
        require(values != null && values.size() == new HashSet<>(values).size()
                        && values.stream().allMatch(value -> value != null && !value.isBlank()),
                "Cover-note " + label + " keys are invalid or duplicated");
    }

    private String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return "the role";
        String normalized = value.strip().replaceAll("[\\p{Cc}\\p{Cf}]", "")
                .replaceAll("\\s+", " ");
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CoverNoteTruthValidationException(message);
    }
}
