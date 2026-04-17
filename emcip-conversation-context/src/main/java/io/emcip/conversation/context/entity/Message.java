package io.emcip.conversation.context.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * JPA entity representing a message in a conversation.
 * Stores the full message content and metadata for context.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
    name = "messages",
    indexes = {
        @Index(name = "idx_messages_thread_time", columnList = "thread_telegram_chat_id,created_at"),
        @Index(name = "idx_messages_sender", columnList = "sender_telegram_id"),
        @Index(name = "idx_messages_event_id", columnList = "event_id", unique = true)
    }
)
public class Message {

    public enum MessageRole {
        USER,           // Regular user message
        ADMIN,          // Admin/moderator message
        BOT,            // Bot response
        SYSTEM          // System message
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36, unique = true)
    private String eventId;

    @Column(name = "telegram_message_id", nullable = false)
    private Long telegramMessageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_telegram_chat_id", nullable = false)
    private MessageThread thread;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_telegram_id", nullable = false)
    private User sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 16)
    private MessageRole senderRole = MessageRole.USER;

    @Column(name = "text_content", nullable = false, length = 4000)
    private String textContent;

    @Column(name = "is_edited", nullable = false)
    private Boolean isEdited = false;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @Column(name = "telegram_timestamp", nullable = false)
    private Instant telegramTimestamp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edit_timestamp")
    private Instant editTimestamp;

    @Column(name = "reply_to_message_id")
    private Long replyToMessageId;

    @Column(name = "intent_classification", length = 32)
    private String intentClassification;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "metadata", length = 2000)
    private String metadata;

}
