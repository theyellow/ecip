package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for the JWT and service-token authentication filters. Exercises the filter logic
 * directly via mock ServerWebExchange, avoiding the need for a full Spring web context.
 */
class SecurityFilterChainTest {

    private static final String TEST_SECRET = "test-secret-must-be-at-least-32ch!!";
    private static final String DEFAULT_SERVICE_TOKEN = "internal-service-token";

    private JwtService jwtService;
    private JwtAuthenticationFilter jwtFilter;
    private ServiceTokenAuthenticationFilter serviceTokenFilter;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", TEST_SECRET);

        jwtFilter = new JwtAuthenticationFilter(jwtService);

        serviceTokenFilter = new ServiceTokenAuthenticationFilter();
        ReflectionTestUtils.setField(
                serviceTokenFilter, "configuredServiceToken", DEFAULT_SERVICE_TOKEN);
    }

    // --- JwtAuthenticationFilter tests ---

    @Test
    void jwtFilter_noAuthorizationHeader_chainContinuesWithoutAuthentication() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/groups").build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        AtomicReference<Authentication> capturedAuth = new AtomicReference<>();

        WebFilterChain chain =
                ex ->
                        ReactiveSecurityContextHolder.getContext()
                                .map(SecurityContext::getAuthentication)
                                .doOnNext(capturedAuth::set)
                                .then(Mono.fromRunnable(() -> chainInvoked.set(true)));

        StepVerifier.create(jwtFilter.filter(exchange, chain)).verifyComplete();

        assertThat(chainInvoked.get()).isTrue();
        assertThat(capturedAuth.get()).isNull();
    }

    @Test
    void jwtFilter_invalidJwt_chainContinuesWithoutAuthentication() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/groups")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token")
                                .build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(jwtFilter.filter(exchange, chain)).verifyComplete();

        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void jwtFilter_validJwt_chainRunsWithAuthenticationInContext() {
        String token = jwtService.generateToken("admin", "ADMIN", null, null);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/groups")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .build());

        AtomicReference<Authentication> capturedAuth = new AtomicReference<>();

        WebFilterChain chain =
                ex ->
                        ReactiveSecurityContextHolder.getContext()
                                .map(SecurityContext::getAuthentication)
                                .doOnNext(capturedAuth::set)
                                .then();

        StepVerifier.create(jwtFilter.filter(exchange, chain)).verifyComplete();

        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().getName()).isEqualTo("admin");
        assertThat(capturedAuth.get().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    // --- ServiceTokenAuthenticationFilter tests ---

    @Test
    void serviceTokenFilter_noHeader_chainContinuesWithoutAuthentication() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/groups").build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(serviceTokenFilter.filter(exchange, chain)).verifyComplete();

        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void serviceTokenFilter_invalidToken_returns401AndChainNotInvoked() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/groups")
                                .header("X-Service-Token", "wrong-token")
                                .build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(serviceTokenFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked.get()).isFalse();
    }

    @Test
    void serviceTokenFilter_validToken_chainRunsWithServiceRoleInContext() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/groups")
                                .header("X-Service-Token", DEFAULT_SERVICE_TOKEN)
                                .build());

        AtomicReference<Authentication> capturedAuth = new AtomicReference<>();

        WebFilterChain chain =
                ex ->
                        ReactiveSecurityContextHolder.getContext()
                                .map(SecurityContext::getAuthentication)
                                .doOnNext(capturedAuth::set)
                                .then();

        StepVerifier.create(serviceTokenFilter.filter(exchange, chain)).verifyComplete();

        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_SERVICE"));
    }

    @Test
    void validTenantAdminJwt_populatesPermissionsAndDetails() {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(
                jwtService, "secret", "test-secret-key-must-be-32-chars-minimum!!");
        UUID tenantId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String token = jwtService.generateToken("alice", "TENANT_ADMIN", tenantId, "Acme Corp");

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/telegram/accounts")
                                .header("Authorization", "Bearer " + token)
                                .build());

        AtomicReference<org.springframework.security.core.Authentication> capturedAuth =
                new AtomicReference<>();
        WebFilterChain chain =
                ex ->
                        ReactiveSecurityContextHolder.getContext()
                                .doOnNext(ctx -> capturedAuth.set(ctx.getAuthentication()))
                                .then();

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        org.springframework.security.core.Authentication auth = capturedAuth.get();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(a -> a.getAuthority())
                .contains("ROLE_TENANT_ADMIN", "TELEGRAM_READ", "TELEGRAM_WRITE")
                .doesNotContain("AI_CONFIG_READ", "TENANTS_READ");
        assertThat(auth.getDetails()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }
}
