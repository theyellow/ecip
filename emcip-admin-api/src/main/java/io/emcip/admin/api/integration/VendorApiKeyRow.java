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

@Table("ke_vendor_api_keys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorApiKeyRow {

    @Id private UUID id;

    @Column("vendor_id")
    private String vendorId;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("api_key")
    private String apiKey;

    @Column("enabled")
    private boolean enabled;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
