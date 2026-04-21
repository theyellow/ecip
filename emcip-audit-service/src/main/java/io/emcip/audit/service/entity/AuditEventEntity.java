package io.emcip.audit.service.entity;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("audit_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventEntity {

    @Id private Long id;

    @Column("event_id")
    private String eventId;

    @Column("event_type")
    private String eventType;

    @Column("correlation_id")
    private String correlationId;

    @Column("source_service")
    private String sourceService;

    @Column("action")
    private String action;

    @Column("actor_type")
    private String actorType;

    @Column("actor_id")
    private String actorId;

    @Column("resource_type")
    private String resourceType;

    @Column("resource_id")
    private String resourceId;

    @Column("outcome")
    private String outcome;

    /** JSON string stored in the JSONB details column. */
    @Column("details")
    private String details;

    @Column("processing_time_ms")
    private Integer processingTimeMs;

    @Column("created_at")
    private Instant createdAt;
}
