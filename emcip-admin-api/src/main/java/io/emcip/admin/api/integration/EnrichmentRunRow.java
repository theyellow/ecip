package io.emcip.admin.api.integration;

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

@Table("ke_enrichment_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentRunRow {

    @Id private UUID id;

    @Column("source_id")
    private UUID sourceId;

    @Column("trigger_type")
    private String triggerType;

    @Column("started_at")
    private Instant startedAt;

    @Column("completed_at")
    private Instant completedAt;

    @Column("status")
    private String status;

    @Column("items_fetched")
    private int itemsFetched;

    @Column("items_ingested")
    private int itemsIngested;

    @Column("error_message")
    private String errorMessage;
}
