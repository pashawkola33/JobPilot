package com.jobpilot.candidate.service;

public class CandidateProfileValidationException extends IllegalArgumentException {
    public CandidateProfileValidationException(String message) {
        super(message);
    }
}
