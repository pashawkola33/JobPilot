package com.jobpilot.resume.domain;

public record DocumentArtifactMetadata(String relativePath, String sha256, long size,
                                       Integer pageCount) {
    public DocumentArtifactMetadata(String relativePath, String sha256, long size) {
        this(relativePath, sha256, size, null);
    }

    public DocumentArtifactMetadata {
        if (relativePath == null || relativePath.isBlank()
                || sha256 == null || !sha256.matches("[a-f0-9]{64}") || size <= 0
                || pageCount != null && (pageCount < 1 || pageCount > 2)) {
            throw new IllegalArgumentException("Artifact metadata is incomplete");
        }
    }
}
