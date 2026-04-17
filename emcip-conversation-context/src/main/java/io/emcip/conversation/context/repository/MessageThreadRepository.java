package io.emcip.conversation.context.repository;

import io.emcip.conversation.context.entity.MessageThread;
import io.emcip.conversation.context.entity.MessageThread.ThreadType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for MessageThread entities.
 * Provides CRUD operations and custom queries for thread management.
 */
@Repository
public interface MessageThreadRepository extends JpaRepository<MessageThread, Long> {

    /**
     * Find thread by Telegram chat ID.
     */
    Optional<MessageThread> findByTelegramChatId(Long telegramChatId);

    /**
     * Find all active threads.
     */
    List<MessageThread> findByIsActiveTrue();

    /**
     * Find threads by type.
     */
    List<MessageThread> findByThreadType(ThreadType threadType);

    /**
     * Find threads with activity since given time.
     */
    List<MessageThread> findByLastMessageAtAfter(Instant since);

    /**
     * Check if thread exists by Telegram chat ID.
     */
    boolean existsByTelegramChatId(Long telegramChatId);

    /**
     * Update last message timestamp.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE MessageThread t SET t.lastMessageAt = :timestamp WHERE t.telegramChatId = :chatId")
    int updateLastMessageAt(@Param("chatId") Long chatId, @Param("timestamp") Instant timestamp);

    /**
     * Deactivate a thread.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE MessageThread t SET t.isActive = false WHERE t.telegramChatId = :chatId")
    int deactivate(@Param("chatId") Long chatId);

    /**
     * Count threads by type.
     */
    long countByThreadType(ThreadType threadType);

    /**
     * Find threads ordered by last activity.
     */
    List<MessageThread> findTop20ByIsActiveTrueOrderByLastMessageAtDesc();
}
