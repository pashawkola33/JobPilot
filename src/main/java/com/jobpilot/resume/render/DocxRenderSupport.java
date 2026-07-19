package com.jobpilot.resume.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

final class DocxRenderSupport {
    private static final String FONT = "Arial";

    private DocxRenderSupport() {
    }

    static XWPFDocument document(String title) {
        XWPFDocument document = new XWPFDocument();
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        BigInteger value = BigInteger.valueOf(720);
        margins.setTop(value);
        margins.setBottom(value);
        margins.setLeft(value);
        margins.setRight(value);
        document.getProperties().getCoreProperties().setTitle(title);
        document.getProperties().getCoreProperties().setCreator("JobPilot");
        document.getProperties().getCoreProperties().setSubjectProperty(
                "Private human-review application document");
        return document;
    }

    static void name(XWPFDocument document, String text) {
        XWPFParagraph paragraph = paragraph(document, 0, 0);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        run(paragraph, text, true, 16);
    }

    static void centered(XWPFDocument document, String text, boolean bold, int size) {
        XWPFParagraph paragraph = paragraph(document, 0, 0);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        run(paragraph, text, bold, size);
    }

    static void heading(XWPFDocument document, String text) {
        XWPFParagraph paragraph = paragraph(document, 120, 30);
        run(paragraph, text, true, 11);
    }

    static void body(XWPFDocument document, String text) {
        XWPFParagraph paragraph = paragraph(document, 0, 60);
        run(paragraph, text, false, 10);
    }

    static void bodyBoldPrefix(XWPFDocument document, String prefix, String text) {
        XWPFParagraph paragraph = paragraph(document, 60, 30);
        run(paragraph, prefix, true, 10);
        run(paragraph, text, false, 10);
    }

    static void bullet(XWPFDocument document, String text) {
        XWPFParagraph paragraph = paragraph(document, 0, 30);
        paragraph.setIndentationLeft(360);
        paragraph.setIndentationHanging(180);
        run(paragraph, "• " + text, false, 10);
    }

    static byte[] bytes(XWPFDocument document) {
        try (document; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new DocumentRenderException("DOCX rendering failed", exception);
        }
    }

    static String contactLine(String email, String phone, List<String> links) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        values.add(email);
        if (phone != null && !phone.isBlank()) values.add(phone);
        values.addAll(links);
        return String.join(" | ", values);
    }

    private static XWPFParagraph paragraph(XWPFDocument document, int before, int after) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(before);
        paragraph.setSpacingAfter(after);
        paragraph.setSpacingBetween(1.0);
        return paragraph;
    }

    private static void run(XWPFParagraph paragraph, String text, boolean bold, int size) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(size);
        run.setBold(bold);
        run.setText(text == null ? "" : text);
    }
}
