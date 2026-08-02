package com.jobpilot.browser.application;

import com.jobpilot.browser.api.BrowserExtractionRequest;
import com.jobpilot.browser.api.BrowserExtractionResponse;

/** Calls the single fixed worker endpoint and returns a typed response. */
public interface BrowserExtractionClient {
    BrowserExtractionResponse extract(BrowserExtractionRequest request);
}
