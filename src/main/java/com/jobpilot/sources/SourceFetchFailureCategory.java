package com.jobpilot.sources;

/**
 * Bounded, closed vocabulary for the {@code error_summary} prefix.
 *
 * <p>Only the category and an exception type name are ever stored, never a message, URL,
 * response body, credential, or any candidate or vacancy text.
 */
public enum SourceFetchFailureCategory {
    /** The source or its processing threw a RuntimeException. */
    SOURCE_FAILURE,
    /** The executing thread was interrupted, normally by a graceful shutdown. */
    PROCESS_INTERRUPTED,
    /** An Error escaped; terminalization is best effort and the Error is rethrown. */
    UNCAUGHT_ERROR
}
