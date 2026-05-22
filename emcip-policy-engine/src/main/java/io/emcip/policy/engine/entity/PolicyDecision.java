package io.emcip.policy.engine.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;

/**
 * Entity for storing policy evaluation decisions. Each decision represents the outcome of
 * evaluating a policy against an intent classification.
 */
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
@Table(
        name = "policy_decisions",
        indexes = {
            @Index(name = "idx_policy_decisions_source_event_id", columnList = "sourceEventId"),
            @Index(name = "idx_policy_decisions_timestamp", columnList = "timestamp"),
            @Index(name = "idx_policy_decisions_decision", columnList = "decision")
        })
@Data
public class PolicyDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private String sourceEventId;

    @Column(nullable = false)
    private String policyId;

    @Column(nullable = false, length = 32)
    private String decision;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private String originalIntent;

    @Column(nullable = false)
    private Double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> matchedRules;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "signal_status", length = 16, nullable = false)
    private String signalStatus = "NEW";

    @Version private Long version;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
