package com.jobpilot.resume.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class PdfRenderSupport implements AutoCloseable {
    private static final float MARGIN = 50;
    private static final float BOTTOM = 48;
    private static final float WIDTH = PDRectangle.LETTER.getWidth() - 2 * MARGIN;
    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final PDDocument document = new PDDocument();
    private PDPageContentStream content;
    private float y;
    private int pages;

    PdfRenderSupport(String title) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle(title);
        information.setAuthor("JobPilot");
        information.setSubject("Private human-review application document");
        information.setCreator("JobPilot pure-Java renderer");
        information.setProducer("Apache PDFBox");
        document.setDocumentInformation(information);
        newPage();
    }

    void centered(String value, boolean bold, float size, float after) {
        PDFont font = bold ? BOLD : REGULAR;
        String safe = safeText(value);
        ensure(size + after + 4);
        try {
            float textWidth = font.getStringWidth(safe) / 1000f * size;
            drawLine(safe, font, size, Math.max(MARGIN, (PDRectangle.LETTER.getWidth() - textWidth) / 2));
            y -= after;
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    void heading(String value) {
        y -= 5;
        writeWrapped(value, BOLD, 11, 14, 3, MARGIN, WIDTH);
    }

    void paragraph(String value) {
        writeWrapped(value, REGULAR, 10, 12, 5, MARGIN, WIDTH);
    }

    void boldPrefix(String prefix, String remainder) {
        paragraph(prefix + remainder);
    }

    void bullet(String value) {
        writeWrapped("- " + value, REGULAR, 10, 12, 2, MARGIN + 14, WIDTH - 14);
    }

    byte[] bytes() {
        try {
            if (content != null) {
                content.close();
                content = null;
            }
            if (pages > 2) throw new DocumentRenderException("PDF page limit exceeded", null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            document.close();
            return output.toByteArray();
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    private void writeWrapped(String value, PDFont font, float size, float leading,
                              float after, float x, float maximumWidth) {
        List<String> lines = wrap(safeText(value), font, size, maximumWidth);
        ensure(lines.size() * leading + after);
        for (String line : lines) {
            drawLine(line, font, size, x);
            y -= leading;
        }
        y -= after;
    }

    private List<String> wrap(String value, PDFont font, float size, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : value.strip().split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && width(candidate, font, size) > width) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private float width(String value, PDFont font, float size) {
        try {
            return font.getStringWidth(value) / 1000f * size;
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    private void ensure(float required) {
        if (y - required >= BOTTOM) return;
        try {
            if (content != null) content.close();
        } catch (IOException exception) {
            throw failure(exception);
        }
        newPage();
    }

    private void newPage() {
        if (++pages > 2) throw new DocumentRenderException("PDF page limit exceeded", null);
        try {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = PDRectangle.LETTER.getHeight() - MARGIN;
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    private void drawLine(String value, PDFont font, float size, float x) {
        try {
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(x, y);
            content.showText(value);
            content.endText();
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    public static String safeText(String value) {
        if (value == null) return "";
        String normalized = value.replace('—', '-').replace('–', '-')
                .replace('’', '\'').replace('“', '"').replace('”', '"')
                .replace('•', '-').replace('\u00a0', ' ');
        StringBuilder safe = new StringBuilder(normalized.length());
        for (char character : normalized.toCharArray()) {
            if (character == '\n' || character == '\r' || Character.isISOControl(character)) {
                safe.append(' ');
                continue;
            }
            try {
                REGULAR.encode(String.valueOf(character));
                safe.append(character);
            } catch (IOException | IllegalArgumentException unsupported) {
                safe.append('?');
            }
        }
        return safe.toString().replaceAll("\\s+", " ").strip();
    }

    private DocumentRenderException failure(Exception cause) {
        return new DocumentRenderException("PDF rendering failed", cause);
    }

    @Override
    public void close() {
        try {
            if (content != null) content.close();
            document.close();
        } catch (IOException ignored) {
            // Rendering already failed; the caller receives only the sanitized render exception.
        }
    }
}
