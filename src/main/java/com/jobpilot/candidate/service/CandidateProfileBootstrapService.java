package com.jobpilot.candidate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.domain.CandidateProject;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.common.Hashing;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidateProfileBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(CandidateProfileBootstrapService.class);

    private final CandidateProfileRepository profiles;
    private final CandidateProfileDefinitionValidator validator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CandidateProfileBootstrapService(CandidateProfileRepository profiles,
                                            CandidateProfileDefinitionValidator validator,
                                            ObjectMapper objectMapper, Clock clock) {
        this.profiles = profiles;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CandidateProfileBootstrapResult bootstrap(CandidateProfileProperties definition) {
        validator.validate(definition);
        String sourceHash = sourceHash(definition);
        var active = profiles.findByActiveTrue();
        if (active.isPresent()) {
            CandidateProfile current = active.get();
            if (definition.profileVersion() < current.getProfileVersion()) {
                throw new CandidateProfileVersionConflictException(
                        "Configured profile version is older than the active stored version");
            }
            if (definition.profileVersion() == current.getProfileVersion()) {
                if (!sourceHash.equals(current.getSourceHash())) {
                    throw new CandidateProfileVersionConflictException(
                            "Candidate facts changed without increasing profileVersion");
                }
                log.info("Candidate profile bootstrap unchanged: version={}, skills={}, languages={}, projects={}",
                        current.getProfileVersion(), current.getSkills().size(),
                        current.getLanguages().size(), current.getProjects().size());
                return new CandidateProfileBootstrapResult(
                        current.getId(), current.getProfileVersion(), false);
            }
        }

        profiles.findByProfileVersion(definition.profileVersion()).ifPresent(existing -> {
            throw new CandidateProfileVersionConflictException(
                    "Configured profileVersion already exists and cannot be overwritten");
        });

        Instant now = clock.instant();
        active.ifPresent(current -> {
            current.deactivate(now);
            profiles.saveAndFlush(current);
        });
        CandidateProfile created = map(definition, sourceHash, now);
        profiles.saveAndFlush(created);
        log.info("Candidate profile bootstrap created: version={}, skills={}, languages={}, projects={}",
                created.getProfileVersion(), created.getSkills().size(),
                created.getLanguages().size(), created.getProjects().size());
        return new CandidateProfileBootstrapResult(created.getId(), created.getProfileVersion(), true);
    }

    private CandidateProfile map(CandidateProfileProperties definition, String sourceHash, Instant now) {
        CandidateProfile profile = new CandidateProfile(
                definition.profileVersion(), definition.fullName().trim(), definition.location().trim(),
                definition.educationInstitution().trim(), definition.degree().trim(),
                definition.studyStartYear(), definition.studyEndYear(), definition.currentStudent(),
                definition.finalYearStudent(), definition.commercialJavaExperienceYears(),
                sourceHash, now, true);
        definition.skills().forEach(skill -> profile.addSkill(
                skill.stableKey(), skill.normalizedName(), skill.displayName(), skill.category(),
                skill.evidenceText(), skill.active(), skill.displayOrder()));
        definition.languages().forEach(language -> profile.addLanguage(
                language.stableKey(), language.language(), language.verifiedLevel(),
                language.allowedInCv(), language.active(), language.displayOrder()));
        definition.projects().forEach(projectDefinition -> {
            CandidateProject project = profile.addProject(
                    projectDefinition.stableKey(), projectDefinition.name(),
                    projectDefinition.description(), projectDefinition.projectType(),
                    projectDefinition.technologies(), projectDefinition.active(),
                    projectDefinition.displayOrder());
            projectDefinition.bullets().forEach(bullet -> project.addBullet(
                    bullet.stableKey(), bullet.verifiedText(), bullet.keywords(),
                    bullet.active(), bullet.displayOrder()));
        });
        return profile;
    }

    private String sourceHash(CandidateProfileProperties definition) {
        try {
            return Hashing.sha256(objectMapper.writeValueAsString(definition));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not fingerprint candidate profile configuration", exception);
        }
    }
}
