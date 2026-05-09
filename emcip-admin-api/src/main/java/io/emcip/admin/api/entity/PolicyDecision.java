package io.emcip.admin.api.entity;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("policy_decisions")
@Getter
@NoArgsConstructor
public class PolicyDecision {

    @Id private String id;

    @Column("event_id")
    private String eventId;

    @Column("source_event_id")
    private String sourceEventId;

    @Column("policy_id")
    private String policyId;

    @Column("decision")
    private String decision;

    @Column("reason")
    private String reason;

    @Column("original_intent")
    private String originalIntent;

    @Column("confidence")
    private Double confidence;

    @Column("matched_rules")
    private String matchedRules;

    @Column("metadata")
    private String metadata;

    @Column("timestamp")
    private Instant timestamp;

    @Column("signal_status")
    private String signalStatus;
}
