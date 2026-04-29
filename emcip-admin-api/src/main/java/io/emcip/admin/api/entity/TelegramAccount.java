package io.emcip.admin.api.entity;

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

@Table("telegram_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramAccount {

    @Id private UUID id;

    @Column("phone_number")
    private String phoneNumber;

    @Column("api_id")
    private Integer apiId;

    @Column("api_hash")
    private String apiHash;

    @Column("display_name")
    private String displayName;

    @Column("session_string")
    private String sessionString;

    @Column("status")
    private TelegramAccountStatus status;

    @Column("last_error")
    private String lastError;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
