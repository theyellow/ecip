package io.emcip.admin.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

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

    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRY_MS);
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey(), Jwts.SIG.HS256)
                .compact();
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
}
