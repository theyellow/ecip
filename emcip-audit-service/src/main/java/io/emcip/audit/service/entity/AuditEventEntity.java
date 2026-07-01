package io.emcip.audit.service.entity;

import io.r2dbc.postgresql.codec.Json;
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

@Schema(description = "Immutable audit event record capturing an action taken within the system")
@Table("audit_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventEntity {

    @Schema(description = "Internal sequence ID")
    @Id
    private Long id;

    @Schema(description = "Unique event identifier (UUID)", example = "a1b2c3d4-...")
    @Column("event_id")
    private String eventId;

    @Schema(
            description = "Type of audit event",
            example = "POLICY_DECISION",
            allowableValues = {
                "POLICY_DECISION",
                "MODERATION_FLAG",
                "LLM_CALL",
                "AUTH",
                "ADMIN_ACTION"
            })
    @Column("event_type")
    private String eventType;

    @Schema(description = "Correlation ID linking related events across services")
    @Column("correlation_id")
    private String correlationId;

    @Schema(description = "Service that produced this event", example = "policy-engine")
    @Column("source_service")
    private String sourceService;

    @Schema(description = "Action performed", example = "BLOCK")
    @Column("action")
    private String action;

    @Schema(
            description = "Type of actor that triggered the action",
            example = "USER",
            allowableValues = {"USER", "BOT", "SERVICE"})
    @Column("actor_type")
    private String actorType;

    @Schema(description = "Identifier of the actor", example = "user-42")
    @Column("actor_id")
    private String actorId;

    @Schema(description = "Type of resource the action was applied to", example = "MESSAGE")
    @Column("resource_type")
    private String resourceType;

    @Schema(description = "Identifier of the resource", example = "msg-9876")
    @Column("resource_id")
    private String resourceId;

    @Schema(
            description = "Outcome of the action",
            example = "SUCCESS",
            allowableValues = {"SUCCESS", "FAILURE", "PARTIAL"})
    @Column("outcome")
    private String outcome;

    @Schema(description = "Additional structured details as JSON")
    /** JSONB details column — serialized as embedded JSON by R2dbcJsonModule. */
    @Column("details")
    private Json details;

    @Schema(description = "Time taken to process the event in milliseconds", example = "42")
    @Column("processing_time_ms")
    private Integer processingTimeMs;

    @Schema(description = "Tenant this audit event belongs to")
    @Column("tenant_id")
    private UUID tenantId;

    @Schema(description = "Event creation timestamp (UTC)")
    @Column("created_at")
    private Instant createdAt;

    @Schema(description = "SHA-256 hash of key event fields for tamper detection")
    @Column("integrity_hash")
    private String integrityHash;

    @Schema(description = "integrity_hash of the preceding audit event (chain linkage)")
    @Column("prev_hash")
    private String prevHash;
}
