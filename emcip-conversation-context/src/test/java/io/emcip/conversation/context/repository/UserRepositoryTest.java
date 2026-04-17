package io.emcip.conversation.context.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.conversation.context.EnableIfDockerAvailable;
import io.emcip.conversation.context.IntegrationTest;
import io.emcip.conversation.context.entity.User;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for UserRepository. Uses Testcontainers PostgreSQL for database operations.
 * Skipped if Docker is not available.
 */
@Slf4j
@IntegrationTest
@Transactional
@EnableIfDockerAvailable
class UserRepositoryTest {

    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        log.info("Setting up test data");
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Should save and find user by Telegram ID")
    void shouldSaveAndFindUserByTelegramId() {
        // Given
        User user = new User();
        user.setTelegramId(123456789L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setIsBot(false);

        // When
        User saved = userRepository.save(user);
        Optional<User> found = userRepository.findById(123456789L);

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getTelegramId()).isEqualTo(123456789L);
        assertThat(found.get().getUsername()).isEqualTo("testuser");
        assertThat(found.get().getDisplayName()).isEqualTo("Test User");
        log.info("User saved and retrieved successfully: {}", found.get().getDisplayName());
    }

    @Test
    @DisplayName("Should find user by username")
    void shouldFindUserByUsername() {
        // Given
        User user = new User();
        user.setTelegramId(987654321L);
        user.setUsername("johndoe");
        user.setFirstName("John");
        user.setIsBot(false);
        userRepository.save(user);

        // When
        Optional<User> found = userRepository.findByUsername("johndoe");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getFirstName()).isEqualTo("John");
        log.info("Found user by username: {}", found.get().getUsername());
    }

    @Test
    @DisplayName("Should check if user exists by Telegram ID")
    void shouldCheckIfUserExistsByTelegramId() {
        // Given
        User user = new User();
        user.setTelegramId(111222333L);
        user.setUsername("existinguser");
        user.setIsBot(false);
        userRepository.save(user);

        // When & Then
        assertThat(userRepository.existsByTelegramId(111222333L)).isTrue();
        assertThat(userRepository.existsByTelegramId(999888777L)).isFalse();
        log.info("Existence check passed");
    }

    @Test
    @DisplayName("Should update last seen timestamp")
    void shouldUpdateLastSeenAt() {
        // Given
        User user = new User();
        user.setTelegramId(444555666L);
        user.setUsername("activeuser");
        user.setIsBot(false);
        userRepository.save(user);

        Instant now = Instant.now();

        // When
        int updated = userRepository.updateLastSeenAt(444555666L, now);
        Optional<User> found = userRepository.findById(444555666L);

        // Then
        assertThat(updated).isEqualTo(1);
        assertThat(found).isPresent();
        assertThat(found.get().getLastSeenAt()).isNotNull();
        log.info("Last seen updated successfully");
    }

    @Test
    @DisplayName("Should find all users by IDs")
    void shouldFindAllByIds() {
        // Given
        User user1 = new User();
        user1.setTelegramId(100L);
        user1.setUsername("user1");
        user1.setIsBot(false);

        User user2 = new User();
        user2.setTelegramId(200L);
        user2.setUsername("user2");
        user2.setIsBot(false);

        userRepository.save(user1);
        userRepository.save(user2);

        // When
        var users = userRepository.findAllById(java.util.List.of(100L, 200L));

        // Then
        assertThat(users).hasSize(2);
        log.info("Found {} users by IDs", users.size());
    }
}
