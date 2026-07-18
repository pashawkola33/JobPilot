package com.jobpilot.candidate.service;

public class CandidateProfileVersionConflictException extends IllegalStateException {
    public CandidateProfileVersionConflictException(String message) {
        super(message);
    }
}
