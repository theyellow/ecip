package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.audit.AdminAuditPublisher;
import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
import io.emcip.admin.api.security.JwtRevocationService;
import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock private AdminUserRepository userRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AdminAuditPublisher auditPublisher;
    @Mock private JwtRevocationService revocationService;

    @InjectMocks private UserManagementService userManagementService;

    private static final UUID TENANT_ID = UUID.randomUUID();

    private AdminUser adminUser() {
        return AdminUser.builder()
                .id(1L)
                .username("admin")
                .email("admin@example.com")
                .passwordHash("$2a$hash")
                .role(Role.ADMIN)
                .enabled(true)
                .createdAt(Instant.now())
                .build();
    }

    private AdminUser tenantAdminUser() {
        return AdminUser.builder()
                .id(2L)
                .username("tadmin")
                .email("tadmin@example.com")
                .passwordHash("$2a$hash")
                .role(Role.TENANT_ADMIN)
                .tenantId(TENANT_ID)
                .enabled(true)
                .createdAt(Instant.now())
                .build();
    }

    private Tenant tenant(String name) {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setName(name);
        t.setCreatedAt(Instant.now());
        return t;
    }

    @Test
    void createUser_tenantAdmin_requiresTenantId() {
        UserRequest req = new UserRequest();
        req.setUsername("tadmin");
        req.setEmail("tadmin@example.com");
        req.setPassword("secret");
        req.setRole(Role.TENANT_ADMIN);
        req.setTenantId(null);

        StepVerifier.create(userManagementService.create(req))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("tenantId is required"))
                .verify();
    }

    @Test
    void createUser_tenantAdmin_validRequest_savesUser() {
        UserRequest req = new UserRequest();
        req.setUsername("tadmin");
        req.setEmail("tadmin@example.com");
        req.setPassword("secret");
        req.setRole(Role.TENANT_ADMIN);
        req.setTenantId(TENANT_ID);

        when(tenantRepository.existsById(TENANT_ID)).thenReturn(Mono.just(true));
        when(passwordEncoder.encode("secret")).thenReturn("$2a$encoded");
        when(userRepository.save(any())).thenReturn(Mono.just(tenantAdminUser()));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(tenant("Acme Corp")));

        StepVerifier.create(userManagementService.create(req))
                .assertNext(
                        resp -> {
                            assertThat(resp.getUsername()).isEqualTo("tadmin");
                            assertThat(resp.getRole()).isEqualTo(Role.TENANT_ADMIN);
                            assertThat(resp.getTenantId()).isEqualTo(TENANT_ID);
                            assertThat(resp.getTenantName()).isEqualTo("Acme Corp");
                        })
                .verifyComplete();
    }

    @Test
    void deleteUser_lastAdmin_rejected() {
        when(userRepository.findById(1L)).thenReturn(Mono.just(adminUser()));
        when(userRepository.countByRoleAndEnabled(Role.ADMIN, true)).thenReturn(Mono.just(1L));

        StepVerifier.create(userManagementService.delete(1L, "other"))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("last enabled admin"))
                .verify();
    }

    @Test
    void deleteUser_self_rejected() {
        when(userRepository.findById(1L)).thenReturn(Mono.just(adminUser()));

        StepVerifier.create(userManagementService.delete(1L, "admin"))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("Cannot delete your own"))
                .verify();
    }

    @Test
    void deleteUser_tenantAdmin_succeeds() {
        when(userRepository.findById(2L)).thenReturn(Mono.just(tenantAdminUser()));
        when(userRepository.delete(any())).thenReturn(Mono.empty());

        StepVerifier.create(userManagementService.delete(2L, "other")).verifyComplete();
    }

    @Test
    void updateUser_selfDemotion_rejected() {
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(adminUser()));
        when(userRepository.findById(1L)).thenReturn(Mono.just(adminUser()));

        UserRequest req = new UserRequest();
        req.setRole(Role.TENANT_ADMIN);
        req.setTenantId(TENANT_ID);

        StepVerifier.create(userManagementService.update(1L, req, "admin"))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage()
                                                .contains("Cannot remove your own admin role"))
                .verify();
    }

    @Test
    void updateUser_otherUser_succeeds() {
        AdminUser caller = adminUser(); // global ADMIN — no tenant restriction
        AdminUser other =
                AdminUser.builder()
                        .id(2L)
                        .username("other")
                        .email("other@example.com")
                        .passwordHash("$2a$hash")
                        .role(Role.ADMIN)
                        .enabled(true)
                        .createdAt(Instant.now())
                        .build();
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(caller));
        when(userRepository.findById(2L)).thenReturn(Mono.just(other));
        when(tenantRepository.existsById(TENANT_ID)).thenReturn(Mono.just(true));
        when(userRepository.save(any())).thenReturn(Mono.just(tenantAdminUser()));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(tenant("Acme Corp")));

        UserRequest req = new UserRequest();
        req.setRole(Role.TENANT_ADMIN);
        req.setTenantId(TENANT_ID);

        StepVerifier.create(userManagementService.update(2L, req, "admin"))
                .assertNext(resp -> assertThat(resp.getRole()).isEqualTo(Role.TENANT_ADMIN))
                .verifyComplete();
    }

    @Test
    void updateUser_tenantAdmin_cannotUpdateOtherTenantUser() {
        UUID otherTenantId = UUID.randomUUID();
        AdminUser caller =
                AdminUser.builder()
                        .id(10L)
                        .username("tadmin")
                        .role(Role.TENANT_ADMIN)
                        .tenantId(TENANT_ID)
                        .enabled(true)
                        .createdAt(Instant.now())
                        .build();
        AdminUser target =
                AdminUser.builder()
                        .id(11L)
                        .username("target")
                        .role(Role.MODERATOR)
                        .tenantId(otherTenantId)
                        .enabled(true)
                        .createdAt(Instant.now())
                        .build();

        when(userRepository.findByUsername("tadmin")).thenReturn(Mono.just(caller));
        when(userRepository.findById(11L)).thenReturn(Mono.just(target));

        UserRequest req = new UserRequest();
        req.setRole(Role.MODERATOR);
        req.setTenantId(TENANT_ID);

        StepVerifier.create(userManagementService.update(11L, req, "tadmin"))
                .expectErrorMatches(
                        e ->
                                e instanceof AccessDeniedException
                                        && e.getMessage().contains("outside your tenant"))
                .verify();
    }

    @Test
    void updateUser_tenantAdmin_cannotReassignTenant() {
        UUID otherTenantId = UUID.randomUUID();
        AdminUser caller =
                AdminUser.builder()
                        .id(10L)
                        .username("tadmin")
                        .role(Role.TENANT_ADMIN)
                        .tenantId(TENANT_ID)
                        .enabled(true)
                        .createdAt(Instant.now())
                        .build();
        AdminUser target =
                AdminUser.builder()
                        .id(11L)
                        .username("target")
                        .role(Role.MODERATOR)
                        .tenantId(TENANT_ID)
                        .enabled(true)
                        .createdAt(Instant.now())
                        .build();

        when(userRepository.findByUsername("tadmin")).thenReturn(Mono.just(caller));
        when(userRepository.findById(11L)).thenReturn(Mono.just(target));

        UserRequest req = new UserRequest();
        req.setRole(Role.MODERATOR);
        req.setTenantId(otherTenantId);

        StepVerifier.create(userManagementService.update(11L, req, "tadmin"))
                .expectErrorMatches(
                        e ->
                                e instanceof AccessDeniedException
                                        && e.getMessage().contains("reassign"))
                .verify();
    }

    @Test
    void createUser_moderator_requiresTenantId() {
        UserRequest req = new UserRequest();
        req.setUsername("mod");
        req.setEmail("mod@example.com");
        req.setPassword("secret");
        req.setRole(Role.MODERATOR);
        req.setTenantId(null);

        StepVerifier.create(userManagementService.create(req))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("tenantId is required"))
                .verify();
    }

    @Test
    void createUser_analyst_requiresTenantId() {
        UserRequest req = new UserRequest();
        req.setUsername("analyst");
        req.setEmail("analyst@example.com");
        req.setPassword("secret");
        req.setRole(Role.ANALYST);
        req.setTenantId(null);

        StepVerifier.create(userManagementService.create(req))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("tenantId is required"))
                .verify();
    }

    @Test
    void createUser_viewer_requiresTenantId() {
        UserRequest req = new UserRequest();
        req.setUsername("viewer");
        req.setEmail("viewer@example.com");
        req.setPassword("secret");
        req.setRole(Role.VIEWER);
        req.setTenantId(null);

        StepVerifier.create(userManagementService.create(req))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("tenantId is required"))
                .verify();
    }

    @Test
    void createUser_moderator_validRequest_savesUser() {
        UserRequest req = new UserRequest();
        req.setUsername("mod");
        req.setEmail("mod@example.com");
        req.setPassword("secret");
        req.setRole(Role.MODERATOR);
        req.setTenantId(TENANT_ID);

        AdminUser moderator =
                AdminUser.builder()
                        .id(3L)
                        .username("mod")
                        .email("mod@example.com")
                        .passwordHash("$2a$encoded")
                        .role(Role.MODERATOR)
                        .tenantId(TENANT_ID)
                        .enabled(true)
                        .createdAt(Instant.now())
                        .build();

        when(tenantRepository.existsById(TENANT_ID)).thenReturn(Mono.just(true));
        when(passwordEncoder.encode("secret")).thenReturn("$2a$encoded");
        when(userRepository.save(any())).thenReturn(Mono.just(moderator));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(tenant("Acme Corp")));

        StepVerifier.create(userManagementService.create(req))
                .assertNext(
                        resp -> {
                            assertThat(resp.getUsername()).isEqualTo("mod");
                            assertThat(resp.getRole()).isEqualTo(Role.MODERATOR);
                            assertThat(resp.getTenantId()).isEqualTo(TENANT_ID);
                            assertThat(resp.getTenantName()).isEqualTo("Acme Corp");
                        })
                .verifyComplete();
    }

    @Test
    void toResponse_includesLastLogin() {
        Instant loginTime = Instant.parse("2026-06-22T10:00:00Z");
        AdminUser user =
                AdminUser.builder()
                        .id(1L)
                        .username("admin")
                        .email("admin@example.com")
                        .passwordHash("$2a$hash")
                        .role(Role.ADMIN)
                        .enabled(true)
                        .createdAt(Instant.now())
                        .lastLogin(loginTime)
                        .build();

        when(userRepository.findAll()).thenReturn(reactor.core.publisher.Flux.just(user));

        StepVerifier.create(userManagementService.findAll())
                .assertNext(resp -> assertThat(resp.getLastLogin()).isEqualTo(loginTime))
                .verifyComplete();
    }
}
