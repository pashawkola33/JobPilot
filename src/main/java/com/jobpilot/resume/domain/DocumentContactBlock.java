package com.jobpilot.resume.domain;

import java.util.List;

/** Runtime-only private contact data. This value must never be persisted or sent to an LLM. */
public record DocumentContactBlock(String email, String phone, List<String> links) {
    public DocumentContactBlock {
        links = List.copyOf(links);
    }
}
