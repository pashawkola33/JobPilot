package com.jobpilot.resume.application;

import com.jobpilot.resume.domain.CoverNoteDocumentModel;
import com.jobpilot.resume.domain.DocumentContactBlock;
import com.jobpilot.resume.domain.ResumeDocumentModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CoverNoteDraftBuilder {
    public CoverNoteDraftPlan deterministicPlan(CandidateDocumentFacts facts,
                                                JobDocumentFacts job,
                                                ResumeDocumentModel resume) {
        List<String> candidate = new ArrayList<>();
        candidate.add("profile:" + facts.profileVersion());
        resume.skills().stream().limit(5)
                .map(value -> "skill:" + value.stableKey()).forEach(candidate::add);
        resume.projects().stream().limit(2).forEach(project -> {
            candidate.add("project:" + project.stableKey());
            project.bullets().stream().limit(1)
                    .map(value -> "bullet:" + project.stableKey() + ":" + value.stableKey())
                    .forEach(candidate::add);
        });
        List<String> vacancy = new ArrayList<>(List.of("job:title", "job:company"));
        if (job.analysis() != null && !job.analysis().mustHaveRequirements().isEmpty()) {
            vacancy.add("analysis:must-have:0");
        }
        if (job.analysis() != null && !job.analysis().candidateGaps().isEmpty()) {
            vacancy.add("analysis:gap:0");
        }
        return new CoverNoteDraftPlan(candidate, vacancy);
    }

    public CoverNoteDocumentModel build(CandidateDocumentFacts facts, JobDocumentFacts job,
                                        ResumeDocumentModel resume, DocumentContactBlock contact,
                                        CoverNoteDraftPlan plan, String templateVersion) {
        Set<String> selected = new LinkedHashSet<>(plan.candidateFactKeys());
        List<ResumeDocumentModel.Skill> skills = resume.skills().stream()
                .filter(value -> selected.contains("skill:" + value.stableKey())).limit(5).toList();
        List<ResumeDocumentModel.Project> projects = resume.projects().stream()
                .filter(value -> selected.contains("project:" + value.stableKey())).limit(2).toList();
        List<String> evidence = new ArrayList<>();
        evidence.add("job:title=" + bounded(job.title(), 200));
        evidence.add("job:company=" + bounded(job.company(), 200));
        if (plan.vacancyEvidenceKeys().contains("analysis:must-have:0")
                && job.analysis() != null && !job.analysis().mustHaveRequirements().isEmpty()) {
            evidence.add("analysis:must-have:0="
                    + bounded(job.analysis().mustHaveRequirements().getFirst(), 200));
        }
        if (plan.vacancyEvidenceKeys().contains("analysis:gap:0")
                && job.analysis() != null && !job.analysis().candidateGaps().isEmpty()) {
            evidence.add("analysis:gap:0=" + bounded(job.analysis().candidateGaps().getFirst(), 200));
        }
        List<String> paragraphs = expectedParagraphs(facts, job, skills, projects, evidence);
        return new CoverNoteDocumentModel(facts.fullName(), contact, bounded(job.title(), 200),
                bounded(job.company(), 200), "Dear Hiring Team,", paragraphs, "Sincerely,",
                List.copyOf(selected), evidence, templateVersion);
    }

    public static List<String> expectedParagraphs(CandidateDocumentFacts facts,
                                                  JobDocumentFacts job,
                                                  List<ResumeDocumentModel.Skill> skills,
                                                  List<ResumeDocumentModel.Project> projects,
                                                  List<String> evidence) {
        String focus = evidence.stream().filter(value -> value.startsWith("analysis:must-have:0="))
                .map(value -> value.substring(value.indexOf('=') + 1)).findFirst()
                .map(value -> " The vacancy specifically identifies " + value
                        + ", so I have kept this note focused on relevant verified facts.")
                .orElse("");
        String first = "I am writing to apply for the " + bounded(job.title(), 200)
                + " opportunity at " + bounded(job.company(), 200) + "." + focus
                + " I am seeking a student or entry-level setting where careful engineering, "
                + "clear communication, and continued learning are valued.";

        String skillText = natural(skills.stream().map(ResumeDocumentModel.Skill::displayName).toList());
        String second = "I am currently studying " + facts.degree() + " at "
                + facts.educationInstitution() + ". My resume selects only verified skills, including "
                + skillText + ". These are project-based foundations; I do not present them as "
                + "commercial employment or claim professional experience that is not in my profile.";

        String projectText = projects.stream().map(project -> {
            String bullet = project.bullets().isEmpty() ? project.description()
                    : project.bullets().getFirst().verifiedText();
            return project.name() + ", a " + project.projectType().toLowerCase(java.util.Locale.ROOT)
                    .replace('_', ' ') + ": " + bullet;
        }).reduce((left, right) -> left + " I also built " + right).orElse("verified coursework");
        String third = "One example is " + projectText
                + " Every project statement here is drawn from the same verified facts used in the resume, "
                + "without invented metrics, team size, employers, or production-scale claims.";

        String gap = evidence.stream().filter(value -> value.startsWith("analysis:gap:0="))
                .map(value -> value.substring(value.indexOf('=') + 1)).findFirst()
                .map(value -> " I also recognize the identified gap around " + value
                        + "; I would treat it as a learning priority rather than imply existing experience.")
                .orElse("");
        String fourth = "I would welcome a conversation about the verified projects and how I approach "
                + "backend and software-development problems." + gap
                + " Thank you for reviewing my application materials. I will follow the hiring process "
                + "as provided and leave all submission decisions to the human applicant.";
        return List.of(first, second, third, fourth);
    }

    private static String natural(List<String> values) {
        if (values.isEmpty()) return "the skills shown in the attached resume";
        if (values.size() == 1) return values.getFirst();
        return String.join(", ", values.subList(0, values.size() - 1))
                + " and " + values.getLast();
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return "the role";
        String normalized = value.strip().replaceAll("[\\p{Cc}\\p{Cf}]", "")
                .replaceAll("\\s+", " ");
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
