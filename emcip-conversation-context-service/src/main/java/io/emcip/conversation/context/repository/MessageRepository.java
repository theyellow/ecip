package io.emcip.conversation.context.repository;

import io.emcip.conversation.context.entity.Message;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for Message entities.
 * Provides CRUD operations and custom queries for message retrieval.
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Find message by event ID.
     */
    Optional<Message> findByEventId(String eventId);

    /**
     * Find message by Telegram message ID in a specific thread.
     */
    Optional<Message> findByTelegramMessageIdAndThread_TelegramChatId(Long telegramMessageId, Long chatId);

    /**
     * Find all messages in a thread.
     */
    List<Message> findByThread_TelegramChatIdOrderByTelegramTimestampAsc(Long chatId);

    /**
     * Find messages in a thread with pagination.
     */
    Page<Message> findByThread_TelegramChatId(Long chatId, Pageable pageable);

    /**
     * Find recent messages in a thread.
     */
    List<Message> findTop50ByThread_TelegramChatIdOrderByTelegramTimestampDesc(Long chatId);

    /**
     * Find messages by sender.
     */
    List<Message> findBySender_TelegramIdOrderByTelegramTimestampDesc(Long senderId);

    /**
     * Find messages with a specific intent.
     */
    List<Message> findByIntentClassification(String intent);

    /**
     * Find messages with confidence above threshold.
     */
    @Query("SELECT m FROM Message m WHERE m.confidenceScore >= :minConfidence")
    List<Message> findWithHighConfidence(@Param("minConfidence") double minConfidence);

    /**
     * Find messages containing text (case-insensitive).
     */
    @Query("SELECT m FROM Message m WHERE LOWER(m.textContent) LIKE LOWER(CONCAT('%', :text, '%'))")
    List<Message> searchByText(@Param("text") String text);

    /**
     * Find messages in thread after a specific time.
     */
    List<Message> findByThread_TelegramChatIdAndTelegramTimestampAfter(Long chatId, Instant after);

    /**
     * Mark message as deleted.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Message m SET m.isDeleted = true WHERE m.telegramMessageId = :messageId AND m.thread.telegramChatId = :chatId")
    int markAsDeleted(@Param("messageId") Long messageId, @Param("chatId") Long chatId);

    /**
     * Mark message as edited.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Message m SET m.isEdited = true, m.editTimestamp = :editTime WHERE m.telegramMessageId = :messageId AND m.thread.telegramChatId = :chatId")
    int markAsEdited(@Param("messageId") Long messageId, @Param("chatId") Long chatId, @Param("editTime") Instant editTime);

    /**
     * Update intent classification for a message.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Message m SET m.intentClassification = :intent, m.confidenceScore = :confidence WHERE m.eventId = :eventId")
    int updateIntentClassification(@Param("eventId") String eventId, @Param("intent") String intent, @Param("confidence") Double confidence);

    /**
     * Count messages in thread.
     */
    long countByThread_TelegramChatId(Long chatId);

    /**
     * Count messages by sender.
     */
    long countBySender_TelegramId(Long senderId);

    /**
     * Get message statistics by intent.
     */
    @Query("SELECT m.intentClassification, COUNT(m) FROM Message m WHERE m.intentClassification IS NOT NULL GROUP BY m.intentClassification")
    List<Object[]> getIntentStatistics();
}
