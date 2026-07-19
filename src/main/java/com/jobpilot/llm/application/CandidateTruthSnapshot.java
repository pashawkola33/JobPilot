package com.jobpilot.llm.application;

import com.jobpilot.candidate.domain.CandidateLanguage;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.domain.CandidateProject;
import com.jobpilot.candidate.domain.CandidateProjectBullet;
import com.jobpilot.candidate.domain.CandidateSkill;
import com.jobpilot.llm.domain.EvidenceSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CandidateTruthSnapshot(
        Long profileId,
        int profileVersion,
        String truthHash,
        String location,
        String education,
        boolean currentStudent,
        boolean finalYearStudent,
        BigDecimal commercialJavaExperienceYears,
        List<TruthFact> facts) {

    public static CandidateTruthSnapshot from(CandidateProfile profile) {
        Map<String, TruthFact> result = new LinkedHashMap<>();
        result.put("education", new TruthFact("education", EvidenceSource.CANDIDATE_EDUCATION,
                profile.getDegree() + " at " + profile.getEducationInstitution()));
        for (CandidateSkill skill : profile.getSkills()) {
            if (!skill.isActive()) continue;
            result.put(skill.getStableKey(), new TruthFact(skill.getStableKey(),
                    EvidenceSource.CANDIDATE_SKILL,
                    skill.getDisplayName() + ". " + skill.getEvidenceText()));
        }
        for (CandidateLanguage language : profile.getLanguages()) {
            if (!language.isActive()) continue;
            result.put(language.getStableKey(), new TruthFact(language.getStableKey(),
                    EvidenceSource.CANDIDATE_LANGUAGE,
                    language.getLanguage() + " — " + language.getVerifiedLevel().name()));
        }
        for (CandidateProject project : profile.getProjects()) {
            if (!project.isActive()) continue;
            result.put(project.getStableKey(), new TruthFact(project.getStableKey(),
                    EvidenceSource.CANDIDATE_PROJECT,
                    project.getName() + ". " + project.getDescription() + ". "
                            + String.join(", ", project.getTechnologies())));
            for (CandidateProjectBullet bullet : project.getBullets()) {
                if (!bullet.isActive()) continue;
                result.put(bullet.getStableKey(), new TruthFact(bullet.getStableKey(),
                        EvidenceSource.CANDIDATE_PROJECT_BULLET, bullet.getVerifiedText()));
            }
        }
        return new CandidateTruthSnapshot(profile.getId(), profile.getProfileVersion(),
                profile.getSourceHash(), profile.getLocation(),
                profile.getDegree() + " at " + profile.getEducationInstitution(),
                profile.isCurrentStudent(), profile.isFinalYearStudent(),
                profile.getCommercialJavaExperienceYears(), List.copyOf(result.values()));
    }

    public Map<String, TruthFact> factsByKey() {
        Map<String, TruthFact> result = new LinkedHashMap<>();
        facts.forEach(fact -> result.put(fact.key(), fact));
        return Map.copyOf(result);
    }

    public record TruthFact(String key, EvidenceSource source, String verifiedText) {
    }
}
