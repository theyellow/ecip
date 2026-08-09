package io.emcip.admin.api.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.audit.AdminAuditPublisher;
import io.emcip.admin.api.controller.GroupProfileController;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.service.GroupProfileService;
import io.emcip.admin.api.util.ClientIp;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end authorization tests that drive requests through the <em>real</em> {@link
 * SecurityConfig} filter chain and reactive method security, authenticating with <em>real</em> JWTs
 * minted by {@link JwtService}.
 *
 * <p>This closes the gap left by the existing suites, none of which proves enforcement:
 *
 * <ul>
 *   <li>{@code ControllerAuthorizationTest} is reflection-only — it asserts the
 *       {@code @PreAuthorize} annotations are <em>present</em>, not that anything acts on them.
 *   <li>{@code SecurityFilterChainTest} exercises {@link JwtAuthenticationFilter} and {@link
 *       ServiceTokenAuthenticationFilter} in isolation against a hand-built {@code WebFilterChain},
 *       never the assembled chain, and never touches method security.
 *   <li>The per-controller tests use {@code WebTestClient.bindToController(...)}, which builds a
 *       standalone handler with no security at all — an endpoint whose {@code @PreAuthorize} is
 *       missing entirely still passes them.
 * </ul>
 *
 * <p>Authentication uses real bearer tokens rather than {@code @WithMockUser} or the reactive
 * {@code mockUser()} mutator. Both of those inject a {@code SecurityContext} directly and therefore
 * skip {@link JwtAuthenticationFilter} and {@link RolePermissions} — the exact role-to-authority
 * mapping the {@code @PreAuthorize} expressions consume. Minting a token exercises that whole path,
 * so a regression in the role→permission table fails these tests too.
 *
 * <p>{@link GroupProfileController} is the subject because the role table expresses a genuine
 * read-vs-write split for it: {@code VIEWER} holds {@code GROUPS_READ} but not {@code
 * GROUPS_WRITE}, while {@code TENANT_ADMIN} holds both. That is the RT2-004 shape — a write
 * endpoint that lost its method-level {@code @PreAuthorize} would become reachable by a read-only
 * principal, which {@link #writeEndpoint_asViewerWithOnlyReadPermission_isForbidden()} catches.
 */
@WebFluxTest(
        controllers = GroupProfileController.class,
        properties = {
            // Both JwtService and ServiceTokenAuthenticationFilter reject their shipped defaults in
            // @PostConstruct (P1 hardening), so both need real non-default values here. They must
            // come from properties rather than ReflectionTestUtils: @Value injection also runs on
            // @Bean-returned instances, so a reflectively-set field would be overwritten before the
            // init callback fires.
            "admin.jwt.secret=test-secret-must-be-at-least-32ch!!",
            "admin.service-token=test-service-token"
        })
@Import({SecurityConfig.class, PreAuthorizeEnforcementTest.RealSecurityBeans.class})
class PreAuthorizeEnforcementTest {

    private static final UUID TENANT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final long CHAT_ID = 12345L;

    /**
     * The chain wires both authentication filters via {@code addFilterAt}, so they must be real
     * beans — a Mockito mock would return {@code null} from {@code filter(..)} and break the chain.
     * The slice does not component-scan {@code @Service}/{@code @Component}, so they are declared
     * here.
     */
    @TestConfiguration
    static class RealSecurityBeans {

        @Bean
        JwtService jwtService() {
            return new JwtService();
        }

        @Bean
        JwtRevocationService jwtRevocationService() {
            return new JwtRevocationService();
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(
                JwtService jwtService, JwtRevocationService revocationService) {
            return new JwtAuthenticationFilter(jwtService, revocationService);
        }

        @Bean
        ServiceTokenAuthenticationFilter serviceTokenAuthenticationFilter() {
            return new ServiceTokenAuthenticationFilter();
        }

        /**
         * The chain also wires {@link RateLimitWebFilter}, so it and its collaborators must be real
         * beans for the same reason the authentication filters are. The limits themselves are
         * deliberately permissive here ({@code RateLimiterRegistry.ofDefaults()}): this suite is
         * about authorization, and a request rejected with 429 before reaching method security
         * would mask exactly what it is meant to prove. Enforcement is proven in {@link
         * RateLimitWebFilterTest}.
         */
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        RateLimiterRegistry rateLimiterRegistry() {
            return RateLimiterRegistry.ofDefaults();
        }

        @Bean
        ClientIp clientIp(MeterRegistry meterRegistry) {
            return new ClientIp(1, meterRegistry);
        }

        @Bean
        RateLimitGroups rateLimitGroups(RateLimiterRegistry registry) {
            return new RateLimitGroups(registry);
        }

        @Bean
        RateLimitWebFilter rateLimitWebFilter(
                ClientIp clientIp, RateLimitGroups groups, MeterRegistry meterRegistry) {
            return new RateLimitWebFilter(clientIp, groups, meterRegistry);
        }
    }

    @Autowired private WebTestClient webTestClient;
    @Autowired private JwtService jwtService;

    @MockitoBean private GroupProfileService groupProfileService;
    @MockitoBean private AdminAuditPublisher auditPublisher;

    @BeforeEach
    void setUp() {
        GroupProfile profile = new GroupProfile();
        profile.setId(1L);
        profile.setTelegramChatId(CHAT_ID);
        profile.setName("Acme Group");

        when(groupProfileService.findAll()).thenReturn(Flux.just(profile));
        when(groupProfileService.delete(CHAT_ID)).thenReturn(Mono.empty());
    }

    /** A real signed bearer token for the given role, exactly as {@code /api/auth/token} issues. */
    private String bearer(String role) {
        return "Bearer " + jwtService.generateToken("test-user", role, TENANT_ID, "Acme Corp");
    }

    private WebTestClient.ResponseSpec getGroups(String role) {
        return webTestClient
                .get()
                .uri("/api/groups")
                .header(HttpHeaders.AUTHORIZATION, bearer(role))
                .exchange();
    }

    /**
     * DELETE, not POST, is the write probe: it carries the same
     * {@code @PreAuthorize("hasAuthority('GROUPS_WRITE')")} but takes no request body. A POST would
     * have its body decoded during argument resolution — i.e. <em>before</em> method security runs
     * — so a decode failure would mask the authorization result behind a 400 and a denial would no
     * longer prove anything.
     */
    private WebTestClient.ResponseSpec deleteGroup(String role) {
        return webTestClient
                .delete()
                .uri("/api/groups/" + CHAT_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(role))
                .exchange();
    }

    // --- Filter chain: unauthenticated access ---

    @Test
    void protectedEndpoint_withoutToken_isRejected() {
        webTestClient.get().uri("/api/groups").exchange().expectStatus().isForbidden();
    }

    @Test
    void protectedEndpoint_withGarbageToken_isRejected() {
        // An unparseable token leaves the context unauthenticated rather than throwing.
        webTestClient
                .get()
                .uri("/api/groups")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void internalPath_withoutServiceRole_isRejected() {
        // `/api/internal/**` requires ROLE_SERVICE. No controller maps this path today, so an
        // authorized request would 404 — a denial proves the rule applies before handler lookup.
        webTestClient.get().uri("/api/internal/anything").exchange().expectStatus().isForbidden();
    }

    // --- Method security: @PreAuthorize enforcement ---

    @Test
    void readEndpoint_asViewerWithReadPermission_isAllowed() {
        getGroups("VIEWER").expectStatus().isOk();
    }

    @Test
    void readEndpoint_asAdmin_isAllowed() {
        getGroups("ADMIN").expectStatus().isOk();
    }

    /**
     * The RT2-004 regression test. {@code VIEWER} holds {@code GROUPS_READ} but not {@code
     * GROUPS_WRITE}, and {@code delete} carries its own
     * {@code @PreAuthorize("hasAuthority('GROUPS_WRITE')")}. If that annotation were dropped, the
     * endpoint would carry no method-level constraint and this read-only principal could create
     * groups.
     */
    @Test
    void writeEndpoint_asViewerWithOnlyReadPermission_isForbidden() {
        deleteGroup("VIEWER").expectStatus().isForbidden();
    }

    @Test
    void writeEndpoint_asTenantAdminWithWritePermission_isAllowed() {
        deleteGroup("TENANT_ADMIN").expectStatus().isNoContent();
    }

    /** ANALYST is read-only across the board — it must not be able to write either. */
    @Test
    void writeEndpoint_asAnalyst_isForbidden() {
        deleteGroup("ANALYST").expectStatus().isForbidden();
    }

    // --- ACCESS_DENIED auditing (AUDIT-DENY) ---

    /**
     * A method-level denial must reach the admin audit trail. Before AUDIT-DENY this advice-handled
     * path published nothing: {@code SecurityConfig}'s {@code accessDeniedHandler} is what
     * publishes the event, and it never runs for {@code @PreAuthorize} denials, so the overwhelming
     * majority of denials went unaudited.
     */
    @Test
    void methodLevelDenial_publishesAccessDeniedAuditEvent() {
        deleteGroup("VIEWER").expectStatus().isForbidden();

        verify(auditPublisher)
                .publish(
                        eq("ACCESS_DENIED"),
                        eq("Endpoint"),
                        eq("/api/groups/" + CHAT_ID),
                        eq("test-user"),
                        any(),
                        any(),
                        eq("DENIED"));
    }

    /**
     * The path-rule branch still audits through {@code SecurityConfig}'s handler, and the two paths
     * stay mutually exclusive — a path-rule denial never reaches a controller, so it is published
     * once, not twice.
     */
    @Test
    void pathRuleDenial_publishesAccessDeniedAuditEventExactlyOnce() {
        webTestClient
                .get()
                .uri("/api/internal/anything")
                .header(HttpHeaders.AUTHORIZATION, bearer("VIEWER"))
                .exchange()
                .expectStatus()
                .isForbidden();

        verify(auditPublisher)
                .publish(
                        eq("ACCESS_DENIED"),
                        eq("Endpoint"),
                        eq("/api/internal/anything"),
                        anyString(),
                        any(),
                        any(),
                        eq("DENIED"));
    }
}
