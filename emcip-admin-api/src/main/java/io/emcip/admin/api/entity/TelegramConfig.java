package io.emcip.admin.api.entity;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("telegram_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramConfig {

    @Id private Long id;

    @Column("phone_number")
    private String phoneNumber;

    @Column("api_id")
    private Integer apiId;

    @Column("api_hash")
    private String apiHash;

    @Column("session_string")
    private String sessionString;

    @Column("updated_at")
    private Instant updatedAt;
}
