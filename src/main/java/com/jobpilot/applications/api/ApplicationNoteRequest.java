package com.jobpilot.applications.api;

import jakarta.validation.constraints.Size;

public record ApplicationNoteRequest(@Size(max = 2000) String notes) {
}
