package com.jobpilot.jobs.domain;

import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "job_scores")
public class JobScore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    private Job job;
    private int score;
    @Enumerated(EnumType.STRING)
    private ScoreBand band;
    private boolean suitable;
    private int formalEligibility;
    private int javaBackend;
    private int traineeQuality;
    private int supportingTechnology;
    private int locationFormat;
    private int experienceCompatibility;
    private int freshness;
    private int penalties;
    @Column(columnDefinition = "text")
    private String strengths;
    @Column(columnDefinition = "text")
    private String risks;
    @Column(columnDefinition = "text")
    private String hardBlockers;
    private Instant scoredAt;

    protected JobScore() {
    }

    public JobScore(Job job, ScoreCard card, Instant scoredAt) {
        this.job = job;
        this.score = card.score();
        this.band = card.band();
        this.suitable = card.suitable();
        this.formalEligibility = card.formalEligibility();
        this.javaBackend = card.javaBackend();
        this.traineeQuality = card.traineeQuality();
        this.supportingTechnology = card.supportingTechnology();
        this.locationFormat = card.locationFormat();
        this.experienceCompatibility = card.experienceCompatibility();
        this.freshness = card.freshness();
        this.penalties = card.penalties();
        this.strengths = join(card.strengths());
        this.risks = join(card.risks());
        this.hardBlockers = join(card.hardBlockers());
        this.scoredAt = scoredAt;
    }

    public ScoreCard toValue() {
        return new ScoreCard(score, band, suitable, formalEligibility, javaBackend,
                traineeQuality, supportingTechnology, locationFormat, experienceCompatibility,
                freshness, penalties, split(strengths), split(risks), split(hardBlockers));
    }

    private static String join(List<String> values) { return String.join("|", values); }
    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split("\\|"));
    }

    public Job getJob() { return job; }
    public int getScore() { return score; }
    public ScoreBand getBand() { return band; }
}
