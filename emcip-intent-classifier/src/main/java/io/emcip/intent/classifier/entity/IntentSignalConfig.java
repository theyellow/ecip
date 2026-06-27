package io.emcip.intent.classifier.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "intent_signal_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentSignalConfig {

    @Id
    @UuidGenerator
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(length = 500)
    private String description;

    @Column(name = "foreign_script_ratio", nullable = false)
    private Double foreignScriptRatio;

    @Column(name = "cyrillic_ratio", nullable = false)
    private Double cyrillicRatio;

    @Column(name = "lookalike_suspicion", nullable = false)
    private Integer lookalikeSuspicion;

    @Column(name = "zero_width_abuse", nullable = false)
    private Integer zeroWidthAbuse;

    @Column(name = "caps_ratio", nullable = false)
    private Double capsRatio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "toxicity_words", columnDefinition = "jsonb", nullable = false)
    private List<String> toxicityWords;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
