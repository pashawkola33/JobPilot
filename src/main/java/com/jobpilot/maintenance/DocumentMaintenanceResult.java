package com.jobpilot.maintenance;

public record DocumentMaintenanceResult(
        int staleResumesRecovered,
        int staleCoverNotesRecovered,
        int partialArtifactsRemoved,
        int orphanArtifactsRemoved) {
    public static DocumentMaintenanceResult empty() {
        return new DocumentMaintenanceResult(0, 0, 0, 0);
    }

    public int totalItems() {
        return staleResumesRecovered + staleCoverNotesRecovered
                + partialArtifactsRemoved + orphanArtifactsRemoved;
    }
}
