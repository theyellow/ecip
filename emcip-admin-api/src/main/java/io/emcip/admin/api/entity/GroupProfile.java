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

@Table("group_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupProfile {

    @Id private Long id;

    @Column("telegram_chat_id")
    private Long telegramChatId;

    private String name;

    private String description;

    @Column("rules_enabled")
    private String rulesEnabled;

    @Column("auto_respond")
    private boolean autoRespond;

    @Column("moderation_level")
    private String moderationLevel;

    @Column("welcome_message")
    private String welcomeMessage;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
