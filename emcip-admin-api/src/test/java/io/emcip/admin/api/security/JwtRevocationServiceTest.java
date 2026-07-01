package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JwtRevocationServiceTest {

    private final JwtRevocationService service = new JwtRevocationService();

    @Test
    void isRevoked_unknownJti_returnsFalse() {
        assertThat(service.isRevoked("unknown-jti")).isFalse();
    }

    @Test
    void revoke_thenIsRevoked_returnsTrue() {
        String jti = "test-jti-123";
        service.revoke(jti, Instant.now().plusSeconds(3600));

        assertThat(service.isRevoked(jti)).isTrue();
    }

    @Test
    void cleanup_removesExpiredEntries() {
        String expiredJti = "expired-jti";
        String activeJti = "active-jti";
        service.revoke(expiredJti, Instant.now().minusSeconds(1));
        service.revoke(activeJti, Instant.now().plusSeconds(3600));

        service.cleanup();

        assertThat(service.isRevoked(expiredJti)).isFalse();
        assertThat(service.isRevoked(activeJti)).isTrue();
    }
}
