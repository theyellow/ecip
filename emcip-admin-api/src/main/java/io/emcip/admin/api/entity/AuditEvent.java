package io.emcip.admin.api.entity;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("audit_events")
@Getter
@NoArgsConstructor
public class AuditEvent {

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

    @Column("details")
    private String details;

    @Column("created_at")
    private Instant createdAt;
}
