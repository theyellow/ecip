package io.emcip.admin.api.entity;

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

@Schema(description = "Configuration profile for a watched Telegram group")
@Table("group_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupProfile {

    @Schema(description = "Internal profile ID")
    @Id
    private Long id;

    @Schema(description = "Telegram chat ID this profile applies to", example = "-1001234567890")
    @Column("telegram_chat_id")
    private Long telegramChatId;

    @Schema(description = "Display name for the group", example = "My Community")
    private String name;

    @Schema(description = "Optional description")
    private String description;

    @Schema(
            description = "JSON array of enabled rule IDs",
            example = "[\"spam-block\",\"greeting-respond\"]")
    @Column("rules_enabled")
    private String rulesEnabled;

    @Schema(description = "Whether the bot should auto-respond in this group")
    @Column("auto_respond")
    private boolean autoRespond;

    @Schema(
            description = "Moderation aggressiveness level",
            example = "MEDIUM",
            allowableValues = {"LOW", "MEDIUM", "HIGH"})
    @Column("moderation_level")
    private String moderationLevel;

    @Schema(
            description = "Message sent when a new member joins",
            example = "Welcome to our community!")
    @Column("welcome_message")
    private String welcomeMessage;

    @Schema(description = "Tenant this group belongs to")
    @Column("tenant_id")
    private UUID tenantId;

    @Schema(description = "Creation timestamp (UTC)")
    @Column("created_at")
    private Instant createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    @Column("updated_at")
    private Instant updatedAt;
}
