package io.emcip.moderation.service.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Schema(description = "Content moderation rule (keyword, regex, or length check)")
@Table("moderation_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationRule {

    @Schema(description = "Unique rule ID")
    @Id
    private Long id;

    @Schema(description = "Rule display name", example = "Block profanity")
    private String name;

    @Schema(
            description = "Rule evaluation type",
            example = "REGEX",
            allowableValues = {"KEYWORD", "REGEX", "LENGTH"})
    @Column("rule_type")
    private String ruleType;

    @Schema(description = "Pattern to match against message text", example = "\\b(bad|worse)\\b")
    private String pattern;

    @Schema(
            description = "Severity of a rule violation",
            example = "HIGH",
            allowableValues = {"LOW", "MEDIUM", "HIGH", "CRITICAL"})
    private String severity;

    @Schema(
            description = "Action to take on match",
            example = "FLAG",
            allowableValues = {"FLAG", "BLOCK", "WARN"})
    private String action;

    @Schema(description = "Whether this rule is currently active")
    private boolean enabled;

    @Schema(description = "Creation timestamp (UTC)")
    @Column("created_at")
    private Instant createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    @Column("updated_at")
    private Instant updatedAt;

    @Schema(description = "Tenant this rule belongs to")
    @Column("tenant_id")
    private UUID tenantId;
}
