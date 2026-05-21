package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock private AdminUserRepository userRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PasswordEncoder passwordEncoder;

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
}
