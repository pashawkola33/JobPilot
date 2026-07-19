package com.jobpilot.resume.application;

import com.jobpilot.resume.domain.ResumeDocumentModel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ResumePreviewBuilder {
    public String build(ResumeDocumentModel model, int maximumCharacters) {
        List<String> lines = new ArrayList<>();
        lines.add(model.fullName());
        lines.add(model.selectedRoleTitle());
        lines.add("");
        lines.add("SUMMARY");
        lines.add(model.professionalSummary());
        lines.add("");
        lines.add("TECHNICAL SKILLS");
        lines.add(String.join(", ", model.skills().stream()
                .map(ResumeDocumentModel.Skill::displayName).toList()));
        lines.add("");
        lines.add("PROJECTS");
        for (ResumeDocumentModel.Project project : model.projects()) {
            lines.add(project.name());
            for (ResumeDocumentModel.Bullet bullet : project.bullets()) {
                lines.add("- " + bullet.verifiedText());
            }
        }
        lines.add("");
        lines.add("EDUCATION");
        lines.add(model.education().degree() + ", " + model.education().institution());
        lines.add("");
        lines.add("LANGUAGES");
        lines.add(String.join(", ", model.languages().stream()
                .map(value -> value.language() + " — " + value.verifiedLevel()).toList()));
        String preview = String.join("\n", lines);
        if (preview.length() <= maximumCharacters) return preview;
        int boundary = preview.lastIndexOf('\n', maximumCharacters - 1);
        return preview.substring(0, Math.max(0, boundary)) + "\n[preview truncated]";
    }
}
