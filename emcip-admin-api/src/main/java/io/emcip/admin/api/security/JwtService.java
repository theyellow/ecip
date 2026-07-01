package io.emcip.admin.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public record TokenWithJti(String token, String jti) {}

    @Value("${admin.jwt.secret}")
    private String secret;

    public static final long EXPIRY_MS = 60 * 60 * 1000L;

    public static final long REFRESH_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L;

    @PostConstruct
    void validateSecret() {
        if ("changeme-in-production-32chars-secret".equals(secret)) {
            throw new IllegalStateException(
                    "ADMIN_JWT_SECRET must be set to a strong random value. "
                            + "The default 'changeme-in-production-32chars-secret' is not"
                            + " acceptable for production.");
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public TokenWithJti generateTokenWithJti(
            String username, String role, @Nullable UUID tenantId, @Nullable String tenantName) {
        String jti = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRY_MS);
        var builder =
                Jwts.builder()
                        .id(jti)
                        .subject(username)
                        .claim("role", role)
                        .issuedAt(now)
                        .expiration(expiry);
        if (tenantId != null) {
            builder.claim("tenantId", tenantId.toString());
        }
        if (tenantName != null) {
            builder.claim("tenantName", tenantName);
        }
        return new TokenWithJti(builder.signWith(signingKey(), Jwts.SIG.HS256).compact(), jti);
    }

    public String generateToken(
            String username, String role, @Nullable UUID tenantId, @Nullable String tenantName) {
        return generateTokenWithJti(username, role, tenantId, tenantName).token();
    }

    public Claims validateToken(String token) {
        return Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token).getPayload();
    }

    public String extractUsername(String token) {
        return validateToken(token).getSubject();
    }

    public String extractRole(String token) {
        return validateToken(token).get("role", String.class);
    }

    @Nullable
    public String extractTenantId(String token) {
        return validateToken(token).get("tenantId", String.class);
    }

    @Nullable
    public String extractTenantName(String token) {
        return validateToken(token).get("tenantName", String.class);
    }

    @Nullable
    public String extractJti(String token) {
        return validateToken(token).getId();
    }
}
