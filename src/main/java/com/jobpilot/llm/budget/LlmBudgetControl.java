package com.jobpilot.llm.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "llm_budget_control")
public class LlmBudgetControl {
    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected LlmBudgetControl() {
    }

    public void touch(Instant now) {
        updatedAt = now;
    }
}
