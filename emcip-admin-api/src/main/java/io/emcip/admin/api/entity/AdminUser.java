package io.emcip.admin.api.entity;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("admin_users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUser {

    @Id private Long id;

    private String username;

    private String email;

    @Column("password_hash")
    private String passwordHash;

    private String role;

    private boolean enabled;

    @Column("last_login")
    private Instant lastLogin;

    @Column("created_at")
    private Instant createdAt;
}
