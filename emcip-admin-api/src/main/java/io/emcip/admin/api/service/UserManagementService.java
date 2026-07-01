package io.emcip.admin.api.service;

import io.emcip.admin.api.audit.AdminAuditPublisher;
import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.dto.UserResponse;
import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final AdminUserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditPublisher auditPublisher;

    public Flux<UserResponse> findAll() {
        return userRepository.findAll().flatMap(this::toResponse);
    }

    public Mono<UserResponse> create(UserRequest req) {
        return validateRequest(req)
                .then(
                        Mono.defer(
                                () -> {
                                    AdminUser user =
                                            AdminUser.builder()
                                                    .username(req.getUsername())
                                                    .email(req.getEmail())
                                                    .passwordHash(
                                                            passwordEncoder.encode(
                                                                    req.getPassword()))
                                                    .role(req.getRole())
                                                    .tenantId(req.getTenantId())
                                                    .enabled(true)
                                                    .createdAt(Instant.now())
                                                    .build();
                                    return userRepository.save(user);
                                }))
                .doOnSuccess(
                        user ->
                                auditPublisher.publish(
                                        "USER_CREATED",
                                        "User",
                                        user.getId().toString(),
                                        "system",
                                        user.getTenantId(),
                                        Map.of(
                                                "username",
                                                user.getUsername(),
                                                "role",
                                                user.getRole().name())))
                .flatMap(this::toResponse);
    }

    public Mono<UserResponse> update(Long id, UserRequest req, String callerUsername) {
        return userRepository
                .findByUsername(callerUsername)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "Caller not found")))
                .flatMap(
                        caller ->
                                userRepository
                                        .findById(id)
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND,
                                                                "User not found")))
                                        .flatMap(
                                                user -> {
                                                    if (user.getUsername().equals(callerUsername)
                                                            && user.getRole() == Role.ADMIN
                                                            && req.getRole() != Role.ADMIN) {
                                                        return Mono.error(
                                                                new ResponseStatusException(
                                                                        HttpStatus.BAD_REQUEST,
                                                                        "Cannot remove your own"
                                                                                + " admin role"));
                                                    }
                                                    // TENANT_ADMIN may only update users within
                                                    // their own tenant
                                                    if (caller.getRole() != Role.ADMIN) {
                                                        if (user.getTenantId() == null
                                                                || !user.getTenantId()
                                                                        .equals(
                                                                                caller
                                                                                        .getTenantId())) {
                                                            return Mono.error(
                                                                    new AccessDeniedException(
                                                                            "Cannot update users"
                                                                                    + " outside"
                                                                                    + " your"
                                                                                    + " tenant"));
                                                        }
                                                        // Only ADMIN may reassign a user to a
                                                        // different tenant
                                                        if (req.getTenantId() != null
                                                                && !req.getTenantId()
                                                                        .equals(
                                                                                caller
                                                                                        .getTenantId())) {
                                                            return Mono.error(
                                                                    new AccessDeniedException(
                                                                            "Only ADMIN may"
                                                                                    + " reassign"
                                                                                    + " users to a"
                                                                                    + " different"
                                                                                    + " tenant"));
                                                        }
                                                    }
                                                    return validateRequest(req).thenReturn(user);
                                                })
                                        .flatMap(
                                                user -> {
                                                    user.setRole(req.getRole());
                                                    user.setTenantId(req.getTenantId());
                                                    if (req.getEnabled() != null)
                                                        user.setEnabled(req.getEnabled());
                                                    return userRepository.save(user);
                                                })
                                        .doOnSuccess(
                                                user ->
                                                        auditPublisher.publish(
                                                                "USER_UPDATED",
                                                                "User",
                                                                user.getId().toString(),
                                                                callerUsername,
                                                                user.getTenantId(),
                                                                Map.of(
                                                                        "role",
                                                                        req.getRole().name())))
                                        .flatMap(this::toResponse));
    }

    public Mono<Void> delete(Long id, String callerUsername) {
        return userRepository
                .findById(id)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(
                        user -> {
                            if (user.getUsername().equals(callerUsername)) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "Cannot delete your own account"));
                            }
                            if (user.getRole() == Role.ADMIN) {
                                return userRepository
                                        .countByRoleAndEnabled(Role.ADMIN, true)
                                        .flatMap(
                                                count -> {
                                                    if (count <= 1) {
                                                        return Mono.error(
                                                                new ResponseStatusException(
                                                                        HttpStatus.BAD_REQUEST,
                                                                        "Cannot delete the last"
                                                                                + " enabled admin"
                                                                                + " user"));
                                                    }
                                                    return userRepository
                                                            .delete(user)
                                                            .doOnSuccess(
                                                                    v ->
                                                                            auditPublisher.publish(
                                                                                    "USER_DELETED",
                                                                                    "User",
                                                                                    id.toString(),
                                                                                    callerUsername,
                                                                                    user
                                                                                            .getTenantId(),
                                                                                    Map.of(
                                                                                            "username",
                                                                                            user
                                                                                                    .getUsername())));
                                                });
                            }
                            return userRepository
                                    .delete(user)
                                    .doOnSuccess(
                                            v ->
                                                    auditPublisher.publish(
                                                            "USER_DELETED",
                                                            "User",
                                                            id.toString(),
                                                            callerUsername,
                                                            user.getTenantId(),
                                                            Map.of(
                                                                    "username",
                                                                    user.getUsername())));
                        });
    }

    public Mono<Void> resetPassword(Long id, String newPassword) {
        return userRepository
                .findById(id)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(
                        user -> {
                            user.setPasswordHash(passwordEncoder.encode(newPassword));
                            return userRepository.save(user);
                        })
                .doOnSuccess(
                        user ->
                                auditPublisher.publish(
                                        "PASSWORD_CHANGED",
                                        "User",
                                        id.toString(),
                                        "system",
                                        user.getTenantId(),
                                        null))
                .then();
    }

    private Mono<Void> validateRequest(UserRequest req) {
        if (req.getRole() != Role.ADMIN && req.getTenantId() == null) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "tenantId is required for " + req.getRole() + " role"));
        }
        if (req.getRole() == Role.ADMIN && req.getTenantId() != null) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "ADMIN role must not have a tenantId"));
        }
        if (req.getRole() != Role.ADMIN && req.getTenantId() != null) {
            return tenantRepository
                    .existsById(req.getTenantId())
                    .flatMap(
                            exists ->
                                    exists
                                            ? Mono.empty()
                                            : Mono.error(
                                                    new ResponseStatusException(
                                                            HttpStatus.BAD_REQUEST,
                                                            "Tenant not found")));
        }
        return Mono.empty();
    }

    private Mono<UserResponse> toResponse(AdminUser user) {
        Mono<String> tenantNameMono =
                user.getTenantId() != null
                        ? tenantRepository
                                .findById(user.getTenantId())
                                .map(t -> t.getName())
                                .defaultIfEmpty("")
                        : Mono.just("");

        return tenantNameMono.map(
                tenantName ->
                        UserResponse.builder()
                                .id(user.getId())
                                .username(user.getUsername())
                                .email(user.getEmail())
                                .role(user.getRole())
                                .tenantId(user.getTenantId())
                                .tenantName(tenantName.isEmpty() ? null : tenantName)
                                .enabled(user.isEnabled())
                                .createdAt(user.getCreatedAt())
                                .lastLogin(user.getLastLogin())
                                .build());
    }
}
