package io.emcip.admin.api.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("policy_rules")
@Data
public class PolicyRule {

    @Id private String id;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("target_intent")
    private String targetIntent;

    @Column("min_confidence")
    private Double minConfidence;

    @Column("max_confidence")
    private Double maxConfidence;

    @Column("action")
    private String action;

    @Column("reason")
    private String reason;

    @Column("priority")
    private Integer priority;

    @Column("active")
    private Boolean active;

    @Column("rule_version")
    private Integer ruleVersion;

    @Column("effective_from")
    private Instant effectiveFrom;

    @Column("effective_to")
    private Instant effectiveTo;

    @Column("created_at")
    private Instant createdAt;

    @Column("tenant_id")
    private UUID tenantId;
}
