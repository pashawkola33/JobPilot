package com.jobpilot.resume.storage;

import com.jobpilot.common.Hashing;
import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentArtifactMetadata;
import com.jobpilot.resume.domain.DocumentFormat;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class DocumentArtifactStorage {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final DocumentProperties properties;
    private final DocumentFilenamePolicy filenames;
    private final DocumentArtifactValidator validator;

    public DocumentArtifactStorage(DocumentProperties properties,
                                   DocumentFilenamePolicy filenames,
                                   DocumentArtifactValidator validator) {
        this.properties = properties;
        this.filenames = filenames;
        this.validator = validator;
    }

    public DocumentArtifactBundle store(DocumentKind kind, long documentId,
                                        Map<DocumentFormat, byte[]> rendered,
                                        List<String> expectedText) {
        Path root = requireRoot();
        if (rendered == null || rendered.isEmpty()) {
            throw new DocumentStorageException("At least one rendered format is required");
        }
        Map<DocumentFormat, DocumentArtifactMetadata> stored = new EnumMap<>(DocumentFormat.class);
        List<Path> temporary = new ArrayList<>();
        List<Path> finalized = new ArrayList<>();
        try {
            for (DocumentFormat format : DocumentFormat.values()) {
                byte[] bytes = rendered.get(format);
                if (bytes == null) continue;
                long maximum = maximum(format);
                if (bytes.length <= 0 || bytes.length > maximum) {
                    throw new ArtifactValidationException("Rendered artifact exceeds its size bound");
                }
                String relative = filenames.generatedRelativePath(kind, documentId, format);
                Path target = filenames.resolve(root, relative);
                Path parent = target.getParent();
                secureDirectory(parent);
                rejectSymlinkChain(root, parent);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(target)) {
                    throw new DocumentStorageException("Document target is a symbolic link");
                }
                Path temp = Files.createTempFile(parent, ".jobpilot-", ".partial");
                temporary.add(temp);
                restrictFile(temp);
                Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                validator.validate(temp, format, maximum, expectedText);
                atomicMove(temp, target);
                temporary.remove(temp);
                finalized.add(target);
                restrictFile(target);
                int pageCount = validator.validate(target, format, maximum, expectedText);
                byte[] finalBytes = Files.readAllBytes(target);
                if (finalBytes.length != bytes.length) {
                    throw new ArtifactValidationException("Final artifact size changed unexpectedly");
                }
                stored.put(format, new DocumentArtifactMetadata(relative,
                        Hashing.sha256(finalBytes), finalBytes.length,
                        format == DocumentFormat.PDF ? pageCount : null));
            }
            return new DocumentArtifactBundle(stored.get(DocumentFormat.DOCX),
                    stored.get(DocumentFormat.PDF));
        } catch (ArtifactValidationException exception) {
            cleanup(temporary);
            cleanup(finalized);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            cleanup(temporary);
            cleanup(finalized);
            if (exception instanceof DocumentStorageException storage) throw storage;
            throw new DocumentStorageException("Private document storage failed", exception);
        }
    }

    public byte[] read(DocumentArtifactMetadata metadata, DocumentFormat format) {
        if (metadata == null) throw new DocumentStorageException("Document artifact is unavailable");
        Path path = filenames.resolve(requireRoot(), metadata.relativePath());
        rejectSymlinkChain(requireRoot(), path);
        validator.validate(path, format, maximum(format), List.of());
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != metadata.size() || !Hashing.sha256(bytes).equals(metadata.sha256())) {
                throw new ArtifactValidationException("Stored artifact hash or size does not match metadata");
            }
            return bytes;
        } catch (IOException exception) {
            throw new DocumentStorageException("Private document artifact could not be read", exception);
        }
    }

    public boolean isValid(DocumentArtifactMetadata metadata, DocumentFormat format) {
        try {
            read(metadata, format);
            return true;
        } catch (ArtifactValidationException | DocumentStorageException invalid) {
            return false;
        }
    }

    public void deleteGeneratedBundle(DocumentKind kind, long documentId) {
        Path root = requireRoot();
        List<Path> paths = new ArrayList<>();
        for (DocumentFormat format : DocumentFormat.values()) {
            paths.add(filenames.resolve(root, filenames.generatedRelativePath(kind, documentId, format)));
        }
        cleanup(paths);
        cleanupGeneratedPartials(filenames.resolve(root,
                filenames.generatedRelativePath(kind, documentId, DocumentFormat.DOCX))
                .getParent(), 16);
    }

    public int cleanupOrphans(Set<String> referencedRelativePaths, Instant olderThan, int limit) {
        Set<String> referenced = new HashSet<>(referencedRelativePaths == null
                ? Set.of() : referencedRelativePaths);
        return cleanupOrphans(referenced::contains, olderThan, limit, Duration.ofMinutes(1));
    }

    public int cleanupOrphans(Predicate<String> referenced, Instant olderThan, int limit,
                              Duration maxDuration) {
        if (limit < 1 || limit > 1_000 || olderThan == null) {
            throw new IllegalArgumentException("Orphan cleanup bounds are invalid");
        }
        requireDuration(maxDuration);
        Path root = requireRoot();
        Predicate<String> referenceCheck = referenced == null ? ignored -> false : referenced;
        int removed = 0;
        int visited = 0;
        int scanLimit = Math.max(100, limit * 20);
        long deadline = System.nanoTime() + maxDuration.toNanos();
        try (Stream<Path> paths = Files.walk(root, 3)) {
            var iterator = paths.iterator();
            while (iterator.hasNext() && removed < limit && visited < scanLimit
                    && System.nanoTime() < deadline) {
                Path path = iterator.next();
                visited++;
                if (Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = root.relativize(path).toString().replace(path.getFileSystem()
                        .getSeparator(), "/");
                try {
                    filenames.resolve(root, relative);
                } catch (DocumentStorageException unexpectedName) {
                    continue;
                }
                try {
                    if (!referenceCheck.test(relative)
                            && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                            .toInstant().isBefore(olderThan)
                            && Files.deleteIfExists(path)) {
                        removed++;
                    }
                } catch (IOException | RuntimeException isolated) {
                    // Isolate a single unsafe, unreadable, or concurrently changed candidate.
                }
            }
            return removed;
        } catch (IOException exception) {
            throw new DocumentStorageException("Bounded orphan cleanup failed", exception);
        }
    }

    public int cleanupPartials(Instant olderThan, int limit, Duration maxDuration) {
        if (limit < 1 || limit > 1_000 || olderThan == null) {
            throw new IllegalArgumentException("Partial cleanup bounds are invalid");
        }
        requireDuration(maxDuration);
        Path root = requireRoot();
        int removed = 0;
        int visited = 0;
        int scanLimit = Math.max(100, limit * 20);
        long deadline = System.nanoTime() + maxDuration.toNanos();
        try (Stream<Path> paths = Files.walk(root, 3)) {
            var iterator = paths.iterator();
            while (iterator.hasNext() && removed < limit && visited < scanLimit
                    && System.nanoTime() < deadline) {
                Path path = iterator.next();
                visited++;
                if (Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = root.relativize(path).toString().replace(
                        path.getFileSystem().getSeparator(), "/");
                if (!relative.matches("(?:resumes|cover-notes)/[1-9][0-9]*/"
                        + "\\.jobpilot-[A-Za-z0-9-]{1,80}\\.partial")) continue;
                try {
                    if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                            .toInstant().isBefore(olderThan) && Files.deleteIfExists(path)) {
                        removed++;
                    }
                } catch (IOException isolated) {
                    // Isolate one unreadable or concurrently changed partial.
                }
            }
            return removed;
        } catch (IOException exception) {
            throw new DocumentStorageException("Bounded partial cleanup failed", exception);
        }
    }

    public boolean isReady() {
        if (!properties.enabled()) return true;
        try {
            Path root = requireRoot();
            return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(root) && Files.isWritable(root);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private Path requireRoot() {
        if (!properties.enabled()) {
            throw new DocumentStorageException("Document generation is disabled");
        }
        Path root = properties.storageRoot().toAbsolutePath().normalize();
        for (Path segment : root) {
            String value = segment.toString().toLowerCase(java.util.Locale.ROOT);
            if (value.equals("src") || value.equals("resources") || value.equals("static")
                    || value.equals("public") || value.equals("target")) {
                throw new DocumentStorageException(
                        "Document storage root cannot be inside source or public build directories");
            }
        }
        secureDirectory(root);
        rejectSymlinkChain(root, root);
        return root;
    }

    private void secureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new DocumentStorageException("Document directory is not a private directory");
            }
            try {
                Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
            } catch (UnsupportedOperationException ignored) {
                // The platform has no POSIX mode bits; path and symlink checks still apply.
            }
        } catch (IOException exception) {
            throw new DocumentStorageException("Private document directory could not be prepared", exception);
        }
    }

    private void rejectSymlinkChain(Path root, Path path) {
        if (!path.normalize().startsWith(root)) {
            throw new DocumentStorageException("Document path escapes the private root");
        }
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new DocumentStorageException("Document storage root is a symbolic link");
        }
        Path relative = root.relativize(path.normalize());
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new DocumentStorageException("Document target path contains a symbolic link");
            }
        }
    }

    private void restrictFile(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // The platform has no POSIX mode bits.
        }
    }

    private void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long maximum(DocumentFormat format) {
        return format == DocumentFormat.DOCX
                ? properties.maxDocxBytes() : properties.maxPdfBytes();
    }

    private void requireDuration(Duration maxDuration) {
        if (maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()
                || maxDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Artifact cleanup duration is invalid");
        }
    }

    private void cleanup(List<Path> paths) {
        for (Path path : paths) {
            try {
                if (path != null && !Files.isSymbolicLink(path)) Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best effort; bounded orphan cleanup can safely remove a remaining generated file.
            }
        }
    }

    private void cleanupGeneratedPartials(Path directory, int limit) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) return;
        int removed = 0;
        try (Stream<Path> values = Files.list(directory)) {
            for (Path value : values.sorted().toList()) {
                if (removed >= limit) break;
                String name = value.getFileName().toString();
                if (name.matches("\\.jobpilot-[A-Za-z0-9-]{1,80}\\.partial")
                        && Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(value) && Files.deleteIfExists(value)) {
                    removed++;
                }
            }
        } catch (IOException exception) {
            throw new DocumentStorageException("Private document partial cleanup failed", exception);
        }
    }
}
