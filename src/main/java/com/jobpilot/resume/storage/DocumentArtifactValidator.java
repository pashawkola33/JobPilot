package com.jobpilot.resume.storage;

import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.resume.render.PdfRenderSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class DocumentArtifactValidator {
    private static final int MAX_ZIP_ENTRIES = 512;

    public int validate(Path path, DocumentFormat format, long maximumBytes,
                        List<String> expectedText) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > maximumBytes || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new ArtifactValidationException("Rendered artifact size or file type is invalid");
            }
            if (format == DocumentFormat.DOCX) {
                validateDocx(path, maximumBytes, expectedText);
                return 0;
            }
            return validatePdf(path, expectedText);
        } catch (IOException exception) {
            throw new ArtifactValidationException("Rendered artifact could not be validated", exception);
        }
    }

    private void validateDocx(Path path, long maximumBytes, List<String> expected) throws IOException {
        int entries = 0;
        long expanded = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> values = zip.entries();
            while (values.hasMoreElements()) {
                ZipEntry entry = values.nextElement();
                if (++entries > MAX_ZIP_ENTRIES || entry.getName().contains("..")
                        || entry.getName().startsWith("/")) {
                    throw new ArtifactValidationException("DOCX package structure is invalid");
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.contains("vbaproject") || name.contains("macros")
                        || name.contains("/embeddings/") || name.contains("oleobject")
                        || name.contains("externallinks") || name.contains("comments")) {
                    throw new ArtifactValidationException("DOCX contains a prohibited package part");
                }
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = input.readNBytes((int) Math.min(maximumBytes * 8 + 1, Integer.MAX_VALUE));
                    if (input.read() != -1) {
                        throw new ArtifactValidationException("DOCX expanded content is too large");
                    }
                }
                expanded = Math.addExact(expanded, bytes.length);
                if (expanded > maximumBytes * 8) {
                    throw new ArtifactValidationException("DOCX expanded content is too large");
                }
                if (name.endsWith(".rels")) {
                    String relationships = new String(bytes, StandardCharsets.UTF_8)
                            .toLowerCase(Locale.ROOT);
                    if (relationships.contains("targetmode=\"external\"")) {
                        throw new ArtifactValidationException("DOCX external relationship is prohibited");
                    }
                }
                if (name.endsWith(".xml")) {
                    String xml = new String(bytes, StandardCharsets.UTF_8)
                            .toLowerCase(Locale.ROOT);
                    if (xml.contains("<w:vanish") || xml.contains("<w:webhidden")
                            || xml.contains("<w:trackrevisions") || xml.contains("<w:del")) {
                        throw new ArtifactValidationException("DOCX hidden or tracked content is prohibited");
                    }
                }
            }
        }
        if (entries == 0) throw new ArtifactValidationException("DOCX package is empty");
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(path));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            if (!document.getTables().isEmpty() || !document.getHeaderList().isEmpty()
                    || !document.getFooterList().isEmpty()) {
                throw new ArtifactValidationException("DOCX is not a simple one-column document");
            }
            requireExpected(extractor.getText(), expected, false);
        }
    }

    private int validatePdf(Path path, List<String> expected) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1
                    || document.getNumberOfPages() > 2) {
                throw new ArtifactValidationException("PDF encryption or page count is invalid");
            }
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            if (catalog.getOpenAction() != null || catalog.getAcroForm() != null
                    || hasCatalogActions(catalog)) {
                throw new ArtifactValidationException("PDF contains actions or forms");
            }
            if (catalog.getNames() != null && (catalog.getNames().getEmbeddedFiles() != null
                    || catalog.getNames().getJavaScript() != null)) {
                throw new ArtifactValidationException("PDF contains attachments or JavaScript");
            }
            for (var page : document.getPages()) {
                if (!page.getAnnotations().isEmpty()) {
                    throw new ArtifactValidationException("PDF annotations or actions are prohibited");
                }
            }
            requireExpected(new PDFTextStripper().getText(document), expected, true);
            return document.getNumberOfPages();
        }
    }

    private boolean hasCatalogActions(PDDocumentCatalog catalog) {
        var actions = catalog.getActions();
        return actions != null && (actions.getWC() != null || actions.getWS() != null
                || actions.getDS() != null || actions.getWP() != null || actions.getDP() != null);
    }

    private void requireExpected(String extracted, List<String> expected, boolean pdf) {
        String haystack = normalize(pdf ? PdfRenderSupport.safeText(extracted) : extracted);
        for (String value : expected) {
            String needle = normalize(pdf ? PdfRenderSupport.safeText(value) : value);
            if (!needle.isBlank() && !haystack.contains(needle)) {
                throw new ArtifactValidationException("Rendered artifact is missing canonical text");
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip()
                .toLowerCase(Locale.ROOT);
    }
}
