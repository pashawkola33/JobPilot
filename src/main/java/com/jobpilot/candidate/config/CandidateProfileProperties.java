package com.jobpilot.candidate.config;

import com.jobpilot.candidate.domain.CandidateLanguageLevel;
import com.jobpilot.candidate.domain.CandidateSkillCategory;
import com.jobpilot.candidate.domain.ProjectType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("jobpilot.candidate-profile")
public record CandidateProfileProperties(
        @Positive int profileVersion,
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Size(max = 300) String location,
        @NotBlank @Size(max = 300) String educationInstitution,
        @NotBlank @Size(max = 300) String degree,
        @Min(1950) @Max(2100) int studyStartYear,
        @Min(1950) @Max(2100) Integer studyEndYear,
        boolean currentStudent,
        boolean finalYearStudent,
        @NotNull @DecimalMin("0.0") @DecimalMax("80.0") BigDecimal commercialJavaExperienceYears,
        @NotEmpty @Size(max = 120) List<@Valid Skill> skills,
        @NotEmpty @Size(max = 20) List<@Valid Language> languages,
        @NotEmpty @Size(max = 20) List<@Valid Project> projects) {

    public record Skill(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String stableKey,
            @NotBlank @Size(max = 200) String normalizedName,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull CandidateSkillCategory category,
            @NotBlank @Size(max = 1000) String evidenceText,
            boolean active,
            @PositiveOrZero int displayOrder) {
    }

    public record Language(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String stableKey,
            @NotBlank @Size(max = 100) String language,
            @NotNull CandidateLanguageLevel verifiedLevel,
            boolean allowedInCv,
            boolean active,
            @PositiveOrZero int displayOrder) {
    }

    public record Project(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String stableKey,
            @NotBlank @Size(max = 300) String name,
            @NotBlank @Size(max = 2000) String description,
            @NotNull ProjectType projectType,
            @NotEmpty @Size(max = 40) List<@NotBlank @Size(max = 200) String> technologies,
            boolean active,
            @PositiveOrZero int displayOrder,
            @NotEmpty @Size(max = 80) List<@Valid Bullet> bullets) {
    }

    public record Bullet(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String stableKey,
            @NotBlank @Size(max = 1000) String verifiedText,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> keywords,
            boolean active,
            @PositiveOrZero int displayOrder) {
    }
}
