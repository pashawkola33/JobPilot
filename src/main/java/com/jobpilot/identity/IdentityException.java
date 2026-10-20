package com.jobpilot.identity;

public class IdentityException extends RuntimeException {
    private final int status;
    private final String category;
    public IdentityException(int status, String category) {
        super(category);
        this.status = status;
        this.category = category;
    }
    public int status() { return status; }
    public String category() { return category; }
}
