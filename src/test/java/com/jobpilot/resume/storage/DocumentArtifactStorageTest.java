package com.jobpilot.resume.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentArtifactStorageTest {
    @TempDir Path temporary;

    @Test
    void filenamePolicyRejectsTraversalAbsoluteSeparatorsAndUserControlledNames() {
        DocumentFilenamePolicy policy = new DocumentFilenamePolicy();
        Path root = temporary.toAbsolutePath().normalize();

        assertThat(policy.generatedRelativePath(DocumentKind.RESUME, 42, DocumentFormat.PDF))
                .isEqualTo("resumes/42/resume.pdf");
        assertThatThrownBy(() -> policy.resolve(root, "../resume.pdf"))
                .isInstanceOf(DocumentStorageException.class);
        assertThatThrownBy(() -> policy.resolve(root, "/tmp/resume.pdf"))
                .isInstanceOf(DocumentStorageException.class);
        assertThatThrownBy(() -> policy.resolve(root, "resumes\\42\\resume.pdf"))
                .isInstanceOf(DocumentStorageException.class);
        assertThatThrownBy(() -> policy.resolve(root, "resumes/42/company-role.pdf"))
                .isInstanceOf(DocumentStorageException.class);
    }

    @Test
    void rejectsSymlinkTargetBeforeWritingAndEnforcesSizeBound() throws Exception {
        Path root = temporary.resolve("private-documents");
        Files.createDirectories(root.resolve("resumes"));
        Path outside = temporary.resolve("outside");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(root.resolve("resumes/1"), outside);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("Symbolic links are unavailable on this test filesystem");
        }
        DocumentArtifactStorage storage = storage(root, 1_024);

        assertThatThrownBy(() -> storage.store(DocumentKind.RESUME, 1,
                Map.of(DocumentFormat.DOCX, new byte[1_024]), java.util.List.of()))
                .isInstanceOf(DocumentStorageException.class);
        assertThat(Files.list(outside).toList()).isEmpty();

        DocumentArtifactStorage bounded = storage(temporary.resolve("bounded"), 1_024);
        assertThatThrownBy(() -> bounded.store(DocumentKind.RESUME, 2,
                Map.of(DocumentFormat.DOCX, new byte[1_025]), java.util.List.of()))
                .isInstanceOf(ArtifactValidationException.class);
    }

    @Test
    void boundedOrphanCleanupOnlyDeletesGeneratedUnreferencedOldFiles() throws Exception {
        Path root = temporary.resolve("orphan-root");
        Files.createDirectories(root.resolve("resumes/1"));
        Files.createDirectories(root.resolve("resumes/2"));
        Path referenced = root.resolve("resumes/1/resume.pdf");
        Path orphan = root.resolve("resumes/2/resume.pdf");
        Files.write(referenced, new byte[]{1});
        Files.write(orphan, new byte[]{2});
        FileTime old = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"));
        Files.setLastModifiedTime(referenced, old);
        Files.setLastModifiedTime(orphan, old);

        int removed = storage(root, 2_048).cleanupOrphans(
                Set.of("resumes/1/resume.pdf"), Instant.parse("2026-07-01T00:00:00Z"), 1);

        assertThat(removed).isEqualTo(1);
        assertThat(referenced).exists();
        assertThat(orphan).doesNotExist();
    }

    private DocumentArtifactStorage storage(Path root, long maximum) {
        DocumentProperties properties = new DocumentProperties(true, root, maximum, maximum,
                "resume-v1", "cover-v1", "renderer-v1", 4_000,
                Duration.ofMinutes(10),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                new DocumentProperties.Contact(
                "student@example.test", "", "", "", ""));
        return new DocumentArtifactStorage(properties, new DocumentFilenamePolicy(),
                new DocumentArtifactValidator());
    }
}
