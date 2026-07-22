package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private JwtRevocationService revocationService;
    private JwtAuthenticationFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        revocationService = mock(JwtRevocationService.class);
        filter = new JwtAuthenticationFilter(jwtService, revocationService);
        chain = mock(WebFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchangeWithToken(String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Claims claimsFor(String jti, String username, String role, String tenantId) {
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn(jti);
        when(claims.getSubject()).thenReturn(username);
        when(claims.get("role", String.class)).thenReturn(role);
        when(claims.get("tenantId", String.class)).thenReturn(tenantId);
        return claims;
    }

    @Test
    void revokedTokenIsRejectedWith401AndChainIsNotInvoked() {
        String jti = UUID.randomUUID().toString();
        Claims claims = claimsFor(jti, "alice", "ADMIN", null);
        when(jwtService.validateToken("revoked-token")).thenReturn(claims);
        when(revocationService.isRevoked(jti)).thenReturn(true);

        MockServerWebExchange exchange = exchangeWithToken("revoked-token");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void validTokenPassesThroughChain() {
        String jti = UUID.randomUUID().toString();
        Claims claims = claimsFor(jti, "bob", "VIEWER", null);
        when(jwtService.validateToken("good-token")).thenReturn(claims);
        when(revocationService.isRevoked(jti)).thenReturn(false);

        MockServerWebExchange exchange = exchangeWithToken("good-token");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tokenIsParsedExactlyOncePerRequest() {
        String jti = UUID.randomUUID().toString();
        Claims claims = claimsFor(jti, "bob", "VIEWER", null);
        when(jwtService.validateToken("good-token")).thenReturn(claims);
        when(revocationService.isRevoked(jti)).thenReturn(false);

        StepVerifier.create(filter.filter(exchangeWithToken("good-token"), chain)).verifyComplete();

        verify(jwtService, org.mockito.Mockito.times(1)).validateToken("good-token");
        verify(jwtService, never()).extractUsername(anyString());
        verify(jwtService, never()).extractRole(anyString());
        verify(jwtService, never()).extractJti(anyString());
        verify(jwtService, never()).extractTenantId(anyString());
    }
}
