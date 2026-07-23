package io.emcip.admin.api.service;

import io.emcip.admin.api.audit.AdminAuditPublisher;
import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
import io.emcip.admin.api.security.JwtService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AdminUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final TenantRepository tenantRepository;
    private final AdminAuditPublisher auditPublisher;

    public Mono<TokenResponse> authenticate(String username, String password) {
        return userRepository
                .findByUsername(username)
                .filter(
                        user ->
                                user.isEnabled()
                                        && passwordEncoder.matches(
                                                password, user.getPasswordHash()))
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "Invalid credentials")))
                .flatMap(
                        user ->
                                resolveTenantName(user.getTenantId())
                                        .flatMap(
                                                tenantName -> {
                                                    user.setLastLogin(Instant.now());
                                                    var tokenWithJti =
                                                            jwtService.generateTokenWithJti(
                                                                    user.getUsername(),
                                                                    user.getRole().name(),
                                                                    user.getTenantId(),
                                                                    tenantName.isEmpty()
                                                                            ? null
                                                                            : tenantName);
                                                    user.setCurrentJti(tokenWithJti.jti());
                                                    return userRepository
                                                            .save(user)
                                                            .flatMap(
                                                                    saved ->
                                                                            refreshTokenService
                                                                                    .issue(
                                                                                            saved
                                                                                                    .getId())
                                                                                    .map(
                                                                                            rawRefresh ->
                                                                                                    new TokenResponse(
                                                                                                            tokenWithJti
                                                                                                                    .token(),
                                                                                                            Instant
                                                                                                                    .now()
                                                                                                                    .plusMillis(
                                                                                                                            JwtService
                                                                                                                                    .EXPIRY_MS),
                                                                                                            rawRefresh)));
                                                })
                                        .doOnSuccess(
                                                resp ->
                                                        auditPublisher.publish(
                                                                "LOGIN_SUCCESS",
                                                                "Session",
                                                                user.getUsername(),
                                                                user.getUsername(),
                                                                user.getTenantId(),
                                                                Map.of("ip", "request-context"))));
    }

    public Mono<TokenResponse> refresh(String rawRefreshToken) {
        return refreshTokenService
                .rotate(rawRefreshToken)
                .flatMap(
                        result ->
                                userRepository
                                        .findById(result.userId())
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.UNAUTHORIZED,
                                                                "User not found")))
                                        .flatMap(
                                                user ->
                                                        resolveTenantName(user.getTenantId())
                                                                .flatMap(
                                                                        tenantName -> {
                                                                            var tokenWithJti =
                                                                                    jwtService
                                                                                            .generateTokenWithJti(
                                                                                                    user
                                                                                                            .getUsername(),
                                                                                                    user.getRole()
                                                                                                            .name(),
                                                                                                    user
                                                                                                            .getTenantId(),
                                                                                                    tenantName
                                                                                                                    .isEmpty()
                                                                                                            ? null
                                                                                                            : tenantName);
                                                                            user.setCurrentJti(
                                                                                    tokenWithJti
                                                                                            .jti());
                                                                            return userRepository
                                                                                    .save(user)
                                                                                    .map(
                                                                                            saved ->
                                                                                                    new TokenResponse(
                                                                                                            tokenWithJti
                                                                                                                    .token(),
                                                                                                            Instant
                                                                                                                    .now()
                                                                                                                    .plusMillis(
                                                                                                                            JwtService
                                                                                                                                    .EXPIRY_MS),
                                                                                                            result
                                                                                                                    .newRawToken()));
                                                                        })));
    }

    private Mono<String> resolveTenantName(UUID tenantId) {
        if (tenantId == null) return Mono.just("");
        return tenantRepository.findById(tenantId).map(Tenant::getName).defaultIfEmpty("");
    }
}
