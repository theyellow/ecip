package io.emcip.intent.classifier.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(
        name = "intent_rules",
        indexes = {
            @Index(name = "idx_intent_rules_tenant_active", columnList = "tenant_id, active"),
            @Index(name = "idx_intent_rules_priority", columnList = "priority")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentRule {

    @Id
    @UuidGenerator
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(length = 64, nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "match_mode", length = 8, nullable = false)
    private String matchMode;

    @Column(length = 500, nullable = false)
    private String pattern;

    @Column(length = 32, nullable = false)
    private String intent;

    @Column(nullable = false)
    private Double confidence;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;
}
