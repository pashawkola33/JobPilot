package com.jobpilot.resume.storage;

import com.jobpilot.resume.domain.DocumentFormat;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class DocumentFilenamePolicy {
    public String generatedRelativePath(DocumentKind kind, long id, DocumentFormat format) {
        if (id <= 0) throw new IllegalArgumentException("Persisted document ID is required");
        String directory = kind == DocumentKind.RESUME ? "resumes" : "cover-notes";
        String base = kind == DocumentKind.RESUME ? "resume" : "cover-note";
        return directory + "/" + id + "/" + base + "." + format.name().toLowerCase();
    }

    public Path resolve(Path root, String relativeValue) {
        if (relativeValue == null || relativeValue.isBlank() || relativeValue.length() > 1_000
                || relativeValue.contains("\\") || relativeValue.contains(":")
                || relativeValue.indexOf('\0') >= 0
                || !relativeValue.matches("(?:resumes|cover-notes)/[1-9][0-9]*/"
                + "(?:resume|cover-note)\\.(?:docx|pdf)")) {
            throw new DocumentStorageException("Stored document path is invalid");
        }
        try {
            Path relative = Path.of(relativeValue);
            if (relative.isAbsolute() || relative.normalize().getNameCount() != 3
                    || !relative.equals(relative.normalize())) {
                throw new DocumentStorageException("Stored document path is invalid");
            }
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root)) {
                throw new DocumentStorageException("Stored document path escapes the private root");
            }
            return resolved;
        } catch (InvalidPathException invalid) {
            throw new DocumentStorageException("Stored document path is invalid");
        }
    }
}
