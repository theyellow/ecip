package io.emcip.policy.engine.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "policy_rule_history",
        indexes = {
            @Index(name = "idx_prh_rule_id", columnList = "rule_id"),
            @Index(name = "idx_prh_tenant", columnList = "tenant_id")
        })
@Data
public class PolicyRuleHistory {

    @Id private UUID id;

    @Column(name = "rule_id", nullable = false, length = 36)
    private String ruleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> snapshot;

    @Column(name = "edited_by", length = 64)
    private String editedBy;

    @Column(name = "edited_at", nullable = false)
    private Instant editedAt;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;
}
