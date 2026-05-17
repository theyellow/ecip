package io.emcip.conversation.context.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.conversation.context.EnableIfDockerAvailable;
import io.emcip.conversation.context.IntegrationTest;
import io.emcip.conversation.context.entity.Message;
import io.emcip.conversation.context.entity.Message.MessageRole;
import io.emcip.conversation.context.entity.MessageThread;
import io.emcip.conversation.context.entity.MessageThread.ThreadType;
import io.emcip.conversation.context.entity.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for MessageRepository. Uses Testcontainers PostgreSQL for database operations.
 * Skipped if Docker is not available.
 */
@Slf4j
@IntegrationTest
@Transactional
@EnableIfDockerAvailable
class MessageRepositoryTest {

    @Autowired private MessageRepository messageRepository;

    @Autowired private UserRepository userRepository;

    @Autowired private MessageThreadRepository threadRepository;

    private User testUser;
    private MessageThread testThread;

    @BeforeEach
    void setUp() {
        log.info("Setting up test data");
        messageRepository.deleteAll();
        userRepository.deleteAll();
        threadRepository.deleteAll();

        // Create test user
        testUser = new User();
        testUser.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        testUser.setTelegramId(123456789L);
        testUser.setUsername("testuser");
        testUser.setFirstName("Test");
        testUser.setIsBot(false);
        testUser = userRepository.save(testUser);

        // Create test thread
        testThread = new MessageThread();
        testThread.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        testThread.setTelegramChatId(987654321L);
        testThread.setTitle("Test Thread");
        testThread.setThreadType(ThreadType.PRIVATE);
        testThread.setIsActive(true);
        testThread = threadRepository.save(testThread);
    }

    @Test
    @DisplayName("Should save and find message by event ID")
    void shouldSaveAndFindByEventId() {
        // Given
        Message message = new Message();
        message.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        message.setEventId("evt-123456");
        message.setTelegramMessageId(1L);
        message.setThread(testThread);
        message.setSender(testUser);
        message.setSenderRole(MessageRole.USER);
        message.setTextContent("Hello, World!");
        message.setTelegramTimestamp(Instant.now());
        message.setIsEdited(false);
        message.setIsDeleted(false);

        // When
        Message saved = messageRepository.save(message);
        Optional<Message> found = messageRepository.findByEventId("evt-123456");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getTextContent()).isEqualTo("Hello, World!");
        assertThat(found.get().getSender().getTelegramId()).isEqualTo(123456789L);
        assertThat(found.get().getThread().getTelegramChatId()).isEqualTo(987654321L);
        log.info("Message saved and retrieved: {}", found.get().getEventId());
    }

    @Test
    @DisplayName("Should find messages by thread ordered by time")
    void shouldFindByThreadOrderByTelegramTimestampDesc() {
        // Given
        Instant now = Instant.now();

        Message msg1 = new Message();
        msg1.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        msg1.setEventId("evt-1");
        msg1.setTelegramMessageId(1L);
        msg1.setThread(testThread);
        msg1.setSender(testUser);
        msg1.setTextContent("First message");
        msg1.setTelegramTimestamp(now.minusSeconds(60));
        msg1.setIsEdited(false);
        msg1.setIsDeleted(false);

        Message msg2 = new Message();
        msg2.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        msg2.setEventId("evt-2");
        msg2.setTelegramMessageId(2L);
        msg2.setThread(testThread);
        msg2.setSender(testUser);
        msg2.setTextContent("Second message");
        msg2.setTelegramTimestamp(now);
        msg2.setIsEdited(false);
        msg2.setIsDeleted(false);

        messageRepository.saveAll(List.of(msg1, msg2));

        // When
        List<Message> messages =
                messageRepository.findTop50ByThread_TelegramChatIdOrderByTelegramTimestampDesc(
                        testThread.getTelegramChatId());

        // Then
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getTextContent()).isEqualTo("Second message");
        assertThat(messages.get(1).getTextContent()).isEqualTo("First message");
        log.info("Found {} messages ordered by time", messages.size());
    }

    @Test
    @DisplayName("Should find messages by sender")
    void shouldFindBySender() {
        // Given
        Message message = new Message();
        message.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        message.setEventId("evt-sender");
        message.setTelegramMessageId(10L);
        message.setThread(testThread);
        message.setSender(testUser);
        message.setTextContent("From sender");
        message.setTelegramTimestamp(Instant.now());
        message.setIsEdited(false);
        message.setIsDeleted(false);
        messageRepository.save(message);

        // When
        List<Message> messages =
                messageRepository.findBySender_TelegramIdOrderByTelegramTimestampDesc(
                        testUser.getTelegramId());

        // Then
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getTextContent()).isEqualTo("From sender");
        log.info("Found {} messages from sender", messages.size());
    }

    @Test
    @DisplayName("Should find messages by thread with pagination")
    void shouldFindByThreadWithPagination() {
        // Given
        Instant now = Instant.now();

        for (int i = 1; i <= 25; i++) {
            Message msg = new Message();
            msg.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            msg.setEventId("evt-page-" + i);
            msg.setTelegramMessageId((long) i);
            msg.setThread(testThread);
            msg.setSender(testUser);
            msg.setTextContent("Message " + i);
            msg.setTelegramTimestamp(now.minusSeconds(25 - i));
            msg.setIsEdited(false);
            msg.setIsDeleted(false);
            messageRepository.save(msg);
        }

        // When - get page 1 (10 items)
        Pageable pageable = PageRequest.of(0, 10);
        Page<Message> page1 =
                messageRepository.findByThread_TelegramChatId(
                        testThread.getTelegramChatId(), pageable);

        // Then
        assertThat(page1.getContent()).hasSize(10);
        assertThat(page1.getTotalElements()).isEqualTo(25);
        assertThat(page1.getTotalPages()).isEqualTo(3);
        assertThat(page1.hasNext()).isTrue();
        log.info(
                "Pagination test passed: {} total messages, {} pages",
                page1.getTotalElements(),
                page1.getTotalPages());
    }

    @Test
    @DisplayName("Should check if message exists by event ID")
    void shouldCheckIfExistsByEventId() {
        // Given
        Message message = new Message();
        message.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        message.setEventId("evt-exists");
        message.setTelegramMessageId(100L);
        message.setThread(testThread);
        message.setSender(testUser);
        message.setTextContent("Check existence");
        message.setTelegramTimestamp(Instant.now());
        message.setIsEdited(false);
        message.setIsDeleted(false);
        messageRepository.save(message);

        // When & Then - using findByEventId instead
        assertThat(messageRepository.findByEventId("evt-exists")).isPresent();
        assertThat(messageRepository.findByEventId("evt-nonexistent")).isEmpty();
        log.info("Existence check passed");
    }

    @Test
    @DisplayName("Should find recent messages by thread")
    void shouldFindRecentByThread() {
        // Given
        Instant now = Instant.now();

        Message oldMsg = new Message();
        oldMsg.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        oldMsg.setEventId("evt-old");
        oldMsg.setTelegramMessageId(1L);
        oldMsg.setThread(testThread);
        oldMsg.setSender(testUser);
        oldMsg.setTextContent("Old message");
        oldMsg.setTelegramTimestamp(now.minusSeconds(3600)); // 1 hour ago
        oldMsg.setIsEdited(false);
        oldMsg.setIsDeleted(false);

        Message recentMsg = new Message();
        recentMsg.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        recentMsg.setEventId("evt-recent");
        recentMsg.setTelegramMessageId(2L);
        recentMsg.setThread(testThread);
        recentMsg.setSender(testUser);
        recentMsg.setTextContent("Recent message");
        recentMsg.setTelegramTimestamp(now.minusSeconds(60)); // 1 minute ago
        recentMsg.setIsEdited(false);
        recentMsg.setIsDeleted(false);

        messageRepository.saveAll(List.of(oldMsg, recentMsg));

        // When - find messages from last 10 minutes
        Instant tenMinutesAgo = now.minusSeconds(600);
        List<Message> recent =
                messageRepository.findByThread_TelegramChatIdAndTelegramTimestampAfter(
                        testThread.getTelegramChatId(), tenMinutesAgo);

        // Then
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).getTextContent()).isEqualTo("Recent message");
        log.info("Found {} recent messages", recent.size());
    }

    @Test
    @DisplayName("Should count messages by thread")
    void shouldCountByThread() {
        // Given
        Instant now = Instant.now();

        for (int i = 1; i <= 5; i++) {
            Message msg = new Message();
            msg.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            msg.setEventId("evt-count-" + i);
            msg.setTelegramMessageId((long) i);
            msg.setThread(testThread);
            msg.setSender(testUser);
            msg.setTextContent("Message " + i);
            msg.setTelegramTimestamp(now.minusSeconds(i));
            msg.setIsEdited(false);
            msg.setIsDeleted(false);
            messageRepository.save(msg);
        }

        // When
        long count = messageRepository.countByThread_TelegramChatId(testThread.getTelegramChatId());

        // Then
        assertThat(count).isEqualTo(5);
        log.info("Counted {} messages in thread", count);
    }
}
