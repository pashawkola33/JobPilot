package com.jobpilot.resume.application;

public record DocumentDownload(byte[] bytes, String contentType, String safeFilename) {
    public DocumentDownload {
        bytes = bytes.clone();
    }
}
