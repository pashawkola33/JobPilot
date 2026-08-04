package com.jobpilot.matching.rescore;

import com.jobpilot.common.Hashing;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.matching.ScoreCard;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Versioned, length-prefixed canonical SHA-256 identity for one immutable plan. */
public final class ScoreRescorePlanFingerprint {
    public static final String FORMAT_VERSION = "jobpilot-score-rescore-plan-v1";

    private ScoreRescorePlanFingerprint() {
    }

    public static String fingerprint(List<ScoreRescorePlanEntry> entries) {
        List<ScoreRescorePlanEntry> ordered = entries.stream()
                .sorted(Comparator.comparingLong(ScoreRescorePlanEntry::jobId)).toList();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            field(output, "format", FORMAT_VERSION);
            field(output, "entryCount", Integer.toString(ordered.size()));
            for (ScoreRescorePlanEntry entry : ordered) append(output, entry);
            output.flush();
            return Hashing.sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not fingerprint in-memory rescore plan", impossible);
        }
    }

    private static void append(DataOutputStream output, ScoreRescorePlanEntry value)
            throws IOException {
        field(output, "jobId", Long.toString(value.jobId()));
        field(output, "scoreRowId", Long.toString(value.scoreRowId()));
        field(output, "requirementRowId", Long.toString(value.requirementRowId()));
        field(output, "screeningDisposition", value.screeningDisposition().name());
        field(output, "descriptionHash", value.descriptionHash());
        field(output, "sourceContentHash", value.sourceContentHash());
        field(output, "storedScoredAt", value.storedScoredAt().toString());
        field(output, "storedRequirementJsonHash", value.storedRequirementJsonHash());
        score(output, "oldScore", value.storedScore());
        requirements(output, "oldRequirements", value.storedRequirements());
        score(output, "newScore", value.computedScore());
        requirements(output, "newRequirements", value.computedRequirements());
    }

    private static void score(DataOutputStream output, String prefix, ScoreCard value)
            throws IOException {
        field(output, prefix + ".score", Integer.toString(value.score()));
        field(output, prefix + ".band", value.band().name());
        field(output, prefix + ".suitable", Boolean.toString(value.suitable()));
        field(output, prefix + ".formalEligibility", Integer.toString(value.formalEligibility()));
        field(output, prefix + ".javaBackend", Integer.toString(value.javaBackend()));
        field(output, prefix + ".traineeQuality", Integer.toString(value.traineeQuality()));
        field(output, prefix + ".supportingTechnology",
                Integer.toString(value.supportingTechnology()));
        field(output, prefix + ".locationFormat", Integer.toString(value.locationFormat()));
        field(output, prefix + ".experienceCompatibility",
                Integer.toString(value.experienceCompatibility()));
        field(output, prefix + ".freshness", Integer.toString(value.freshness()));
        field(output, prefix + ".penalties", Integer.toString(value.penalties()));
        list(output, prefix + ".strengths", value.strengths(), false);
        list(output, prefix + ".penaltyRisks", value.risks(), true);
        list(output, prefix + ".blockers", value.hardBlockers(), true);
    }

    private static void requirements(DataOutputStream output, String prefix,
                                     ExtractedRequirements value) throws IOException {
        field(output, prefix + ".seniority", value.seniority());
        field(output, prefix + ".internshipOrTrainee",
                Boolean.toString(value.internshipOrTrainee()));
        field(output, prefix + ".requiredExperienceYears",
                value.requiredExperienceYears() == null ? null
                        : Double.toHexString(value.requiredExperienceYears()));
        field(output, prefix + ".requiredEducation", value.requiredEducation());
        field(output, prefix + ".finalYearMandatory",
                Boolean.toString(value.finalYearMandatory()));
        list(output, prefix + ".technologies", value.technologies(), true);
        list(output, prefix + ".programmingLanguages", value.programmingLanguages(), true);
        list(output, prefix + ".spokenLanguages", value.spokenLanguages(), true);
        field(output, prefix + ".location", value.location());
        field(output, prefix + ".remoteEligibility", value.remoteEligibility());
        list(output, prefix + ".mentorshipSignals", value.mentorshipSignals(), true);
        field(output, prefix + ".workAuthorization", value.workAuthorization());
        field(output, prefix + ".salary", value.salary());
        field(output, prefix + ".applicationDeadline",
                value.applicationDeadline() == null ? null : value.applicationDeadline().toString());
        field(output, prefix + ".extractionMethod", value.extractionMethod());
    }

    private static void list(DataOutputStream output, String name, List<String> values,
                             boolean sortAsSet) throws IOException {
        List<String> canonical = new ArrayList<>(values);
        if (sortAsSet) canonical.sort(Comparator.nullsFirst(String::compareTo));
        field(output, name + ".size", Integer.toString(canonical.size()));
        for (String value : canonical) field(output, name + ".item", value);
    }

    private static void field(DataOutputStream output, String name, String value)
            throws IOException {
        write(output, name);
        output.writeBoolean(value != null);
        if (value != null) write(output, value);
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
