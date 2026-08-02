package com.jobpilot.browser.api;

/** Whether, and why, the browser fallback is invoked for a manual submission. */
public enum BrowserFallbackDecision {
    INVOKE,
    SKIP_DISABLED,
    SKIP_PROTECTED,
    SKIP_NOT_JS_REQUIRED
}
