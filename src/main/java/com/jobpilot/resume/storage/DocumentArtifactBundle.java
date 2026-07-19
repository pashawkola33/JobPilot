package com.jobpilot.resume.storage;

import com.jobpilot.resume.domain.DocumentArtifactMetadata;

public record DocumentArtifactBundle(DocumentArtifactMetadata docx,
                                     DocumentArtifactMetadata pdf) {
}
