package com.jobpilot.manualurl.parse;

public record ManualParseResult(ManualParseStatus status, ParsedManualVacancy vacancy) {
    public static ManualParseResult success(ParsedManualVacancy vacancy) {
        return new ManualParseResult(ManualParseStatus.SUCCESS, vacancy);
    }

    public static ManualParseResult failure(ManualParseStatus status) {
        return new ManualParseResult(status, null);
    }
}
