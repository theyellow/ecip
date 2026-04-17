package io.emcip.conversation.context.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** JPA entity representing a Telegram user. Tracks user information across all conversations. */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(name = "username", length = 32)
    private String username;

    @Column(name = "first_name", length = 64)
    private String firstName;

    @Column(name = "last_name", length = 64)
    private String lastName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Getter(AccessLevel.NONE)
    @Column(name = "is_bot", nullable = false)
    private Boolean isBot = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /** Custom method to generate display name. */
    public String getDisplayName() {
        if (firstName != null && lastName != null) {
            return firstName + " " + lastName;
        }
        return firstName != null
                ? firstName
                : username != null ? username : String.valueOf(telegramId);
    }

    /** Custom getter for isBot field (Lombok generates getBot() by default). */
    public Boolean getIsBot() {
        return isBot;
    }

    /** Setter for isBot field. */
    public void setIsBot(Boolean isBot) {
        this.isBot = isBot;
    }
}
