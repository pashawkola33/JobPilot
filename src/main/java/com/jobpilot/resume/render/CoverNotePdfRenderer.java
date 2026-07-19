package com.jobpilot.resume.render;

import com.jobpilot.resume.domain.CoverNoteDocumentModel;
import org.springframework.stereotype.Component;

@Component
public class CoverNotePdfRenderer {
    public byte[] render(CoverNoteDocumentModel model) {
        try (PdfRenderSupport pdf = new PdfRenderSupport("Cover Note")) {
            pdf.centered(model.candidateName(), true, 16, 3);
            pdf.centered(DocxRenderSupport.contactLine(model.contact().email(),
                    model.contact().phone(), model.contact().links()), false, 9, 3);
            pdf.centered("Cover Note - " + model.roleTitle(), true, 11, 8);
            pdf.paragraph(model.salutation());
            model.paragraphs().forEach(pdf::paragraph);
            pdf.paragraph(model.closing());
            pdf.paragraph(model.candidateName());
            return pdf.bytes();
        }
    }
}
