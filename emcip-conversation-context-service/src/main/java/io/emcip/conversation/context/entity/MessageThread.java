package io.emcip.conversation.context.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA entity representing a conversation thread.
 * A thread can be a private chat, group, or channel.
 */
@Entity
@Table(name = "message_threads")
public class MessageThread {

    public enum ThreadType {
        PRIVATE,      // One-on-one conversation
        GROUP,        // Group chat
        CHANNEL,      // Broadcast channel
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

    public MessageThread() {}

    public MessageThread(Long telegramChatId, String title, ThreadType threadType) {
        this.telegramChatId = telegramChatId;
        this.title = title;
        this.threadType = threadType;
    }

    public Long getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(Long telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ThreadType getThreadType() {
        return threadType;
    }

    public void setThreadType(ThreadType threadType) {
        this.threadType = threadType;
    }

    public Integer getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(Integer memberCount) {
        this.memberCount = memberCount;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(Instant lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}
