package io.emcip.admin.api.service;

import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.security.JwtService;
import java.time.Instant;
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

    public Mono<TokenResponse> authenticate(String username, String password) {
        return userRepository
                .findByUsername(username)
                .filter(
                        user ->
                                user.isEnabled()
                                        && passwordEncoder.matches(
                                                password, user.getPasswordHash()))
                .map(
                        user ->
                                new TokenResponse(
                                        jwtService.generateToken(
                                                user.getUsername(), user.getRole()),
                                        Instant.now().plusMillis(JwtService.EXPIRY_MS)))
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "Invalid credentials")));
    }
}
