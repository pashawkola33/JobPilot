package com.jobpilot.candidate.service;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.candidate.domain.CandidateLanguageLevel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class CandidateProfileDefinitionValidator {
    private final Validator validator;

    public CandidateProfileDefinitionValidator(Validator validator) {
        this.validator = validator;
    }

    public void validate(CandidateProfileProperties profile) {
        List<String> violations = validator.validate(profile).stream()
                .map(this::message)
                .sorted()
                .toList();
        if (!violations.isEmpty()) {
            throw new CandidateProfileValidationException(String.join("; ", violations));
        }
        if (profile.studyEndYear() != null && profile.studyEndYear() < profile.studyStartYear()) {
            fail("studyEndYear must not be before studyStartYear");
        }
        if (profile.finalYearStudent() && !profile.currentStudent()) {
            fail("finalYearStudent requires currentStudent");
        }

        requireUnique(profile.skills(), CandidateProfileProperties.Skill::stableKey,
                "duplicate stable skill key");
        requireUnique(profile.languages(), CandidateProfileProperties.Language::stableKey,
                "duplicate stable language key");
        requireUnique(profile.projects(), CandidateProfileProperties.Project::stableKey,
                "duplicate stable project key");
        requireUnique(profile.skills().stream().filter(CandidateProfileProperties.Skill::active).toList(),
                CandidateProfileProperties.Skill::normalizedName, "duplicate active skill");
        requireUnique(profile.languages().stream().filter(CandidateProfileProperties.Language::active).toList(),
                CandidateProfileProperties.Language::language, "duplicate active language");
        requireUnique(profile.projects().stream().filter(CandidateProfileProperties.Project::active).toList(),
                CandidateProfileProperties.Project::name, "duplicate active project");

        for (CandidateProfileProperties.Language language : profile.languages()) {
            validateLanguage(language);
        }
        for (CandidateProfileProperties.Project project : profile.projects()) {
            requireUnique(project.bullets(), CandidateProfileProperties.Bullet::stableKey,
                    "duplicate project-bullet key in " + project.stableKey());
            requireUnique(project.technologies(), Function.identity(),
                    "duplicate technology in " + project.stableKey());
            for (CandidateProfileProperties.Bullet bullet : project.bullets()) {
                requireUnique(bullet.keywords(), Function.identity(),
                        "duplicate keyword in " + project.stableKey() + "/" + bullet.stableKey());
            }
        }
    }

    private void validateLanguage(CandidateProfileProperties.Language language) {
        String name = normalize(language.language());
        if (name.equals("french") && language.allowedInCv()) {
            fail("French must not be enabled for CV use");
        }
        Set<CandidateLanguageLevel> professionalLevels = Set.of(
                CandidateLanguageLevel.NATIVE,
                CandidateLanguageLevel.FLUENT,
                CandidateLanguageLevel.PROFESSIONAL_WORKING);
        if (name.equals("romanian") && language.allowedInCv()
                && professionalLevels.contains(language.verifiedLevel())) {
            fail("Romanian must not be configured as professional working fluency");
        }
    }

    private <T> void requireUnique(Collection<T> values, Function<T, String> key,
                                   String failureMessage) {
        Set<String> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(normalize(key.apply(value)))) {
                fail(failureMessage);
            }
        }
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String message(ConstraintViolation<CandidateProfileProperties> violation) {
        return violation.getPropertyPath() + " " + violation.getMessage();
    }

    private void fail(String message) {
        throw new CandidateProfileValidationException(message);
    }
}
