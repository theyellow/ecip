package io.emcip.admin.api.integration;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ke_enrichment_sources")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentSourceRow {

    @Id private UUID id;

    @Column("vendor_id")
    private String vendorId;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("enabled")
    private boolean enabled;

    @Column("schedule_cron")
    private String scheduleCron;

    @Column("last_run_at")
    private Instant lastRunAt;

    @Column("last_run_status")
    private String lastRunStatus;

    @Column("config")
    private String config; // raw JSON string

    @Version
    @Column("version")
    private long version;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
