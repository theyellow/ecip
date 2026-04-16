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
import org.hibernate.annotations.CreationTimestamp;

/**
 * JPA entity representing a message in a conversation.
 * Stores the full message content and metadata for context.
 */
@Entity
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

    public Message() {}

    public Message(String eventId, Long telegramMessageId, MessageThread thread, 
                   User sender, String textContent, Instant telegramTimestamp) {
        this.eventId = eventId;
        this.telegramMessageId = telegramMessageId;
        this.thread = thread;
        this.sender = sender;
        this.textContent = textContent;
        this.telegramTimestamp = telegramTimestamp;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Long getTelegramMessageId() {
        return telegramMessageId;
    }

    public void setTelegramMessageId(Long telegramMessageId) {
        this.telegramMessageId = telegramMessageId;
    }

    public MessageThread getThread() {
        return thread;
    }

    public void setThread(MessageThread thread) {
        this.thread = thread;
    }

    public User getSender() {
        return sender;
    }

    public void setSender(User sender) {
        this.sender = sender;
    }

    public MessageRole getSenderRole() {
        return senderRole;
    }

    public void setSenderRole(MessageRole senderRole) {
        this.senderRole = senderRole;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public Boolean getIsEdited() {
        return isEdited;
    }

    public void setIsEdited(Boolean isEdited) {
        this.isEdited = isEdited;
    }

    public Boolean getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Boolean isDeleted) {
        this.isDeleted = isDeleted;
    }

    public Instant getTelegramTimestamp() {
        return telegramTimestamp;
    }

    public void setTelegramTimestamp(Instant telegramTimestamp) {
        this.telegramTimestamp = telegramTimestamp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEditTimestamp() {
        return editTimestamp;
    }

    public void setEditTimestamp(Instant editTimestamp) {
        this.editTimestamp = editTimestamp;
    }

    public Long getReplyToMessageId() {
        return replyToMessageId;
    }

    public void setReplyToMessageId(Long replyToMessageId) {
        this.replyToMessageId = replyToMessageId;
    }

    public String getIntentClassification() {
        return intentClassification;
    }

    public void setIntentClassification(String intentClassification) {
        this.intentClassification = intentClassification;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}
