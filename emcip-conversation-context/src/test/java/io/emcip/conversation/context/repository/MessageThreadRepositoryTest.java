package io.emcip.conversation.context.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.conversation.context.IntegrationTest;
import io.emcip.conversation.context.entity.MessageThread;
import io.emcip.conversation.context.entity.MessageThread.ThreadType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for MessageThreadRepository.
 * Uses Testcontainers PostgreSQL for database operations.
 */
@Slf4j
@IntegrationTest
@Transactional
class MessageThreadRepositoryTest {

    @Autowired
    private MessageThreadRepository threadRepository;

    @BeforeEach
    void setUp() {
        log.info("Setting up test data");
        threadRepository.deleteAll();
    }

    @Test
    @DisplayName("Should save and find thread by Telegram chat ID")
    void shouldSaveAndFindThreadByTelegramChatId() {
        // Given
        MessageThread thread = new MessageThread();
        thread.setTelegramChatId(-1001234567890L);
        thread.setTitle("Test Group");
        thread.setThreadType(ThreadType.GROUP);
        thread.setMemberCount(100);
        thread.setIsActive(true);

        // When
        MessageThread saved = threadRepository.save(thread);
        Optional<MessageThread> found = threadRepository.findByTelegramChatId(-1001234567890L);

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Test Group");
        assertThat(found.get().getThreadType()).isEqualTo(ThreadType.GROUP);
        log.info("Thread saved and retrieved: {}", found.get().getTitle());
    }

    @Test
    @DisplayName("Should find all active threads")
    void shouldFindAllActiveThreads() {
        // Given
        MessageThread active1 = new MessageThread();
        active1.setTelegramChatId(1L);
        active1.setTitle("Active 1");
        active1.setThreadType(ThreadType.PRIVATE);
        active1.setIsActive(true);

        MessageThread active2 = new MessageThread();
        active2.setTelegramChatId(2L);
        active2.setTitle("Active 2");
        active2.setThreadType(ThreadType.GROUP);
        active2.setIsActive(true);

        MessageThread inactive = new MessageThread();
        inactive.setTelegramChatId(3L);
        inactive.setTitle("Inactive");
        inactive.setThreadType(ThreadType.CHANNEL);
        inactive.setIsActive(false);

        threadRepository.saveAll(List.of(active1, active2, inactive));

        // When
        List<MessageThread> activeThreads = threadRepository.findByIsActiveTrue();

        // Then
        assertThat(activeThreads).hasSize(2);
        log.info("Found {} active threads", activeThreads.size());
    }

    @Test
    @DisplayName("Should find threads by type")
    void shouldFindThreadsByType() {
        // Given
        MessageThread privateChat = new MessageThread();
        privateChat.setTelegramChatId(10L);
        privateChat.setTitle("Private");
        privateChat.setThreadType(ThreadType.PRIVATE);
        privateChat.setIsActive(true);

        MessageThread group = new MessageThread();
        group.setTelegramChatId(20L);
        group.setTitle("Group");
        group.setThreadType(ThreadType.GROUP);
        group.setIsActive(true);

        threadRepository.save(privateChat);
        threadRepository.save(group);

        // When
        List<MessageThread> groups = threadRepository.findByThreadType(ThreadType.GROUP);

        // Then
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getTitle()).isEqualTo("Group");
        log.info("Found {} groups", groups.size());
    }

    @Test
    @DisplayName("Should check if thread exists by Telegram chat ID")
    void shouldCheckIfThreadExists() {
        // Given
        MessageThread thread = new MessageThread();
        thread.setTelegramChatId(999L);
        thread.setTitle("Existing");
        thread.setThreadType(ThreadType.CHANNEL);
        thread.setIsActive(true);
        threadRepository.save(thread);

        // When & Then
        assertThat(threadRepository.existsByTelegramChatId(999L)).isTrue();
        assertThat(threadRepository.existsByTelegramChatId(888L)).isFalse();
        log.info("Existence check passed");
    }

    @Test
    @DisplayName("Should update last message timestamp")
    void shouldUpdateLastMessageAt() {
        // Given
        MessageThread thread = new MessageThread();
        thread.setTelegramChatId(777L);
        thread.setTitle("Active Chat");
        thread.setThreadType(ThreadType.PRIVATE);
        thread.setIsActive(true);
        threadRepository.save(thread);

        Instant now = Instant.now();

        // When
        int updated = threadRepository.updateLastMessageAt(777L, now);

        // Then
        assertThat(updated).isEqualTo(1);
        Optional<MessageThread> found = threadRepository.findByTelegramChatId(777L);
        assertThat(found).isPresent();
        assertThat(found.get().getLastMessageAt()).isNotNull();
        log.info("Last message timestamp updated");
    }

    @Test
    @DisplayName("Should deactivate a thread")
    void shouldDeactivateThread() {
        // Given
        MessageThread thread = new MessageThread();
        thread.setTelegramChatId(555L);
        thread.setTitle("To Deactivate");
        thread.setThreadType(ThreadType.GROUP);
        thread.setIsActive(true);
        threadRepository.save(thread);

        // When
        int updated = threadRepository.deactivate(555L);

        // Then
        assertThat(updated).isEqualTo(1);
        Optional<MessageThread> found = threadRepository.findByTelegramChatId(555L);
        assertThat(found).isPresent();
        assertThat(found.get().getIsActive()).isFalse();
        log.info("Thread deactivated successfully");
    }

    @Test
    @DisplayName("Should count threads by type")
    void shouldCountThreadsByType() {
        // Given
        MessageThread group1 = new MessageThread();
        group1.setTelegramChatId(100L);
        group1.setTitle("Group 1");
        group1.setThreadType(ThreadType.GROUP);
        group1.setIsActive(true);

        MessageThread group2 = new MessageThread();
        group2.setTelegramChatId(200L);
        group2.setTitle("Group 2");
        group2.setThreadType(ThreadType.GROUP);
        group2.setIsActive(true);

        MessageThread privateChat = new MessageThread();
        privateChat.setTelegramChatId(300L);
        privateChat.setTitle("Private");
        privateChat.setThreadType(ThreadType.PRIVATE);
        privateChat.setIsActive(true);

        threadRepository.saveAll(List.of(group1, group2, privateChat));

        // When
        long groupCount = threadRepository.countByThreadType(ThreadType.GROUP);
        long privateCount = threadRepository.countByThreadType(ThreadType.PRIVATE);

        // Then
        assertThat(groupCount).isEqualTo(2);
        assertThat(privateCount).isEqualTo(1);
        log.info("Counted {} groups and {} private chats", groupCount, privateCount);
    }
}
