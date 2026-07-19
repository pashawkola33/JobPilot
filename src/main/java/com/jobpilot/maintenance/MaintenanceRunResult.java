package com.jobpilot.maintenance;

public record MaintenanceRunResult(
        boolean ran,
        int expiredLlmReservations,
        DocumentMaintenanceResult documents) {
    public static MaintenanceRunResult skipped() {
        return new MaintenanceRunResult(false, 0, DocumentMaintenanceResult.empty());
    }
}
