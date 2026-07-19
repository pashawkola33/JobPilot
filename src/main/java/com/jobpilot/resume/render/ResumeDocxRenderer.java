package com.jobpilot.resume.render;

import com.jobpilot.resume.domain.ResumeDocumentModel;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class ResumeDocxRenderer {
    public byte[] render(ResumeDocumentModel model) {
        XWPFDocument document = DocxRenderSupport.document("Resume");
        DocxRenderSupport.name(document, model.fullName());
        DocxRenderSupport.centered(document, DocxRenderSupport.contactLine(
                model.contact().email(), model.contact().phone(), model.contact().links()), false, 9);
        DocxRenderSupport.centered(document, model.selectedRoleTitle(), true, 11);

        DocxRenderSupport.heading(document, "SUMMARY");
        DocxRenderSupport.body(document, model.professionalSummary());
        DocxRenderSupport.heading(document, "TECHNICAL SKILLS");
        DocxRenderSupport.body(document, String.join(", ", model.skills().stream()
                .map(ResumeDocumentModel.Skill::displayName).toList()));
        DocxRenderSupport.heading(document, "PROJECTS");
        for (ResumeDocumentModel.Project project : model.projects()) {
            String technologies = project.technologies().isEmpty() ? ""
                    : " — " + String.join(", ", project.technologies());
            DocxRenderSupport.bodyBoldPrefix(document, project.name(), technologies);
            for (ResumeDocumentModel.Bullet bullet : project.bullets()) {
                DocxRenderSupport.bullet(document, bullet.verifiedText());
            }
        }
        DocxRenderSupport.heading(document, "EDUCATION");
        String years = model.education().startYear() + "–"
                + (model.education().endYear() == null ? "Present" : model.education().endYear());
        DocxRenderSupport.bodyBoldPrefix(document, model.education().degree() + ", ",
                model.education().institution() + " | " + years);
        DocxRenderSupport.heading(document, "LANGUAGES");
        DocxRenderSupport.body(document, String.join(", ", model.languages().stream()
                .map(value -> value.language() + " — " + value.verifiedLevel()).toList()));
        return DocxRenderSupport.bytes(document);
    }
}
