package com.jobpilot.browser.api;

/** One bounded extraction request. requestId is an opaque, non-identifying token. */
public record BrowserExtractionRequest(String requestId, String url, String expectedSource) {
}
