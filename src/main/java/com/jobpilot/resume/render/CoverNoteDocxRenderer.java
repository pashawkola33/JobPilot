package com.jobpilot.resume.render;

import com.jobpilot.resume.domain.CoverNoteDocumentModel;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class CoverNoteDocxRenderer {
    public byte[] render(CoverNoteDocumentModel model) {
        XWPFDocument document = DocxRenderSupport.document("Cover Note");
        DocxRenderSupport.name(document, model.candidateName());
        DocxRenderSupport.centered(document, DocxRenderSupport.contactLine(
                model.contact().email(), model.contact().phone(), model.contact().links()), false, 9);
        DocxRenderSupport.centered(document, "Cover Note — " + model.roleTitle(), true, 11);
        DocxRenderSupport.body(document, model.salutation());
        model.paragraphs().forEach(value -> DocxRenderSupport.body(document, value));
        DocxRenderSupport.body(document, model.closing());
        DocxRenderSupport.body(document, model.candidateName());
        return DocxRenderSupport.bytes(document);
    }
}
