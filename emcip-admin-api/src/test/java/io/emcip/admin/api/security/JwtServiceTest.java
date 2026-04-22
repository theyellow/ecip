package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {

    private JwtService jwtService;

    private static final String TEST_SECRET = "test-secret-key-must-be-32-chars-minimum!!";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", TEST_SECRET);
    }

    @Test
    void generateToken_producesValidToken() {
        String token = jwtService.generateToken("admin", "ADMIN");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void validateToken_extractsCorrectClaims() {
        String token = jwtService.generateToken("admin", "ADMIN");

        Claims claims = jwtService.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void extractUsername_returnsCorrectUsername() {
        String token = jwtService.generateToken("testuser", "OPERATOR");

        assertThat(jwtService.extractUsername(token)).isEqualTo("testuser");
    }

    @Test
    void extractRole_returnsCorrectRole() {
        String token = jwtService.generateToken("testuser", "OPERATOR");

        assertThat(jwtService.extractRole(token)).isEqualTo("OPERATOR");
    }

    @Test
    void validateToken_expiredToken_throwsException() {
        String expiredToken =
                Jwts.builder()
                        .subject("expireduser")
                        .claim("role", "ADMIN")
                        .issuedAt(new Date(System.currentTimeMillis() - 10_000L))
                        .expiration(new Date(System.currentTimeMillis() - 5_000L))
                        .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes()), Jwts.SIG.HS256)
                        .compact();

        assertThatThrownBy(() -> jwtService.validateToken(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void validateToken_invalidSignature_throwsException() {
        String tokenWithWrongSignature =
                Jwts.builder()
                        .subject("hacker")
                        .claim("role", "ADMIN")
                        .issuedAt(new Date())
                        .expiration(new Date(System.currentTimeMillis() + 60_000L))
                        .signWith(
                                Keys.hmacShaKeyFor(
                                        "wrong-secret-key-must-also-be-32-chars!!".getBytes()),
                                Jwts.SIG.HS256)
                        .compact();

        assertThatThrownBy(() -> jwtService.validateToken(tokenWithWrongSignature))
                .isInstanceOf(SignatureException.class);
    }
}
