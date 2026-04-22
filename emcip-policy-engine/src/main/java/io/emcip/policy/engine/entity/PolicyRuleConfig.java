package io.emcip.policy.engine.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Entity for storing configurable policy rules. Rules can be defined with conditions based on
 * intent, confidence, and metadata.
 */
@Entity
@Table(
        name = "policy_rules",
        indexes = {
            @Index(name = "idx_policy_rules_active", columnList = "active"),
            @Index(name = "idx_policy_rules_priority", columnList = "priority")
        })
@Data
public class PolicyRuleConfig {

    @Id private String id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 500)
    private String description;

    /** Target intent for this rule (e.g., "SPAM", "GREETING", "QUESTION"). Use * for any intent. */
    @Column(nullable = false, length = 32)
    private String targetIntent;

    /** Minimum confidence threshold (0.0 - 1.0). */
    @Column(nullable = false)
    private Double minConfidence;

    /** Maximum confidence threshold (0.0 - 1.0). Null means no upper bound. */
    private Double maxConfidence;

    /**
     * Policy action to take when rule matches. Examples: BLOCK, ALLOW, RESPOND, ESCALATE, EXECUTE,
     * REVIEW
     */
    @Column(nullable = false, length = 16)
    private String action;

    /** Human-readable reason for the decision. */
    @Column(length = 500)
    private String reason;

    /** Rule priority (lower number = higher priority). First matching rule wins. */
    @Column(nullable = false)
    private Integer priority = 100;

    /** Whether this rule is active. */
    @Column(nullable = false)
    private Boolean active = true;

    /**
     * Business version counter (1, 2, 3...). Separate from the JPA @Version optimistic lock field.
     */
    @Column(nullable = false)
    private Integer ruleVersion = 1;

    /** When this rule version becomes effective. Null = no lower bound. */
    private Instant effectiveFrom;

    /** When this rule version was superseded. Null = still current. */
    private Instant effectiveTo;

    /** Additional conditions as JSON (for complex rules). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditions;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @Version private Long version;

    /** Returns true if this rule is active and temporally effective at the given instant. */
    public boolean isEffectiveAt(Instant at) {
        if (!Boolean.TRUE.equals(active)) return false;
        if (effectiveFrom != null && at.isBefore(effectiveFrom)) return false;
        if (effectiveTo != null && !at.isBefore(effectiveTo)) return false;
        return true;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (active == null) {
            active = true;
        }
        if (priority == null) {
            priority = 100;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
