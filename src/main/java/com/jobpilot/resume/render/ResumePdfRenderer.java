package com.jobpilot.resume.render;

import com.jobpilot.resume.domain.ResumeDocumentModel;
import org.springframework.stereotype.Component;

@Component
public class ResumePdfRenderer {
    public byte[] render(ResumeDocumentModel model) {
        try (PdfRenderSupport pdf = new PdfRenderSupport("Resume")) {
            pdf.centered(model.fullName(), true, 16, 3);
            pdf.centered(DocxRenderSupport.contactLine(model.contact().email(),
                    model.contact().phone(), model.contact().links()), false, 9, 3);
            pdf.centered(model.selectedRoleTitle(), true, 11, 5);
            pdf.heading("SUMMARY");
            pdf.paragraph(model.professionalSummary());
            pdf.heading("TECHNICAL SKILLS");
            pdf.paragraph(String.join(", ", model.skills().stream()
                    .map(ResumeDocumentModel.Skill::displayName).toList()));
            pdf.heading("PROJECTS");
            for (ResumeDocumentModel.Project project : model.projects()) {
                String technologies = project.technologies().isEmpty() ? ""
                        : " - " + String.join(", ", project.technologies());
                pdf.boldPrefix(project.name(), technologies);
                project.bullets().forEach(value -> pdf.bullet(value.verifiedText()));
            }
            pdf.heading("EDUCATION");
            String years = model.education().startYear() + "-"
                    + (model.education().endYear() == null ? "Present" : model.education().endYear());
            pdf.boldPrefix(model.education().degree() + ", ",
                    model.education().institution() + " | " + years);
            pdf.heading("LANGUAGES");
            pdf.paragraph(String.join(", ", model.languages().stream()
                    .map(value -> value.language() + " - " + value.verifiedLevel()).toList()));
            return pdf.bytes();
        }
    }
}
