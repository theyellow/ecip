package io.emcip.conversation.context.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA entity representing a conversation thread. A thread can be a private chat, group, or channel.
 */
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
@Table(name = "message_threads")
@Getter
@Setter
@NoArgsConstructor
public class MessageThread {

    public enum ThreadType {
        PRIVATE, // One-on-one conversation
        GROUP, // Group chat
        CHANNEL, // Broadcast channel
        UNKNOWN
    }

    @Id
    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    @Column(name = "title", length = 128)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "thread_type", nullable = false, length = 16)
    private ThreadType threadType = ThreadType.UNKNOWN;

    @Column(name = "member_count")
    private Integer memberCount;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "metadata", length = 4000)
    private String metadata;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
}
