package io.emcip.conversation.context.repository;

import io.emcip.conversation.context.entity.User;
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
 * Repository for User entities.
 * Provides CRUD operations and custom queries for user management.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find user by Telegram ID.
     */
    Optional<User> findByTelegramId(Long telegramId);

    /**
     * Find user by username.
     */
    Optional<User> findByUsername(String username);

    /**
     * Find users by partial username match (case-insensitive).
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<User> searchByUsername(@Param("query") String query);

    /**
     * Find users active since a given time.
     */
    List<User> findByLastSeenAtAfter(Instant since);

    /**
     * Check if user exists by Telegram ID.
     */
    boolean existsByTelegramId(Long telegramId);

    /**
     * Update last seen timestamp.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastSeenAt = :timestamp WHERE u.telegramId = :telegramId")
    int updateLastSeenAt(@Param("telegramId") Long telegramId, @Param("timestamp") Instant timestamp);

    /**
     * Count total users.
     */
    long count();

    /**
     * Count active users (seen in last 24 hours).
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.lastSeenAt > :since")
    long countActiveSince(@Param("since") Instant since);
}
