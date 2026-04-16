package io.emcip.tdlib.adapter.controller;

import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.model.AuthRequest;
import io.emcip.tdlib.adapter.model.AuthStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final TdLibClient tdLibClient;

    public AuthController(TdLibClient tdLibClient) {
        this.tdLibClient = tdLibClient;
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<AuthStatusResponse>> getStatus() {
        return Mono.just(
                ResponseEntity.ok(
                        new AuthStatusResponse(
                                tdLibClient.isInitialized(),
                                tdLibClient.isAuthorized(),
                                tdLibClient.isAuthorized()
                                        ? "Ready"
                                        : "Waiting for authentication")));
    }

    @PostMapping("/phone")
    public Mono<ResponseEntity<Void>> setPhoneNumber(@RequestBody AuthRequest.PhoneNumber request) {
        return Mono.fromRunnable(
                        () -> {
                            log.info("Setting phone number: {}", request.phoneNumber());
                            tdLibClient.setPhoneNumber(request.phoneNumber());
                        })
                .thenReturn(ResponseEntity.accepted().build());
    }

    @PostMapping("/code")
    public Mono<ResponseEntity<Void>> setCode(@RequestBody AuthRequest.Code request) {
        return Mono.fromRunnable(
                        () -> {
                            log.info("Setting authentication code");
                            tdLibClient.setAuthenticationCode(request.code());
                        })
                .thenReturn(ResponseEntity.accepted().build());
    }

    @PostMapping("/password")
    public Mono<ResponseEntity<Void>> setPassword(@RequestBody AuthRequest.Password request) {
        return Mono.fromRunnable(
                        () -> {
                            log.info("Setting 2FA password");
                            tdLibClient.setPassword(request.password());
                        })
                .thenReturn(ResponseEntity.accepted().build());
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout() {
        return Mono.fromRunnable(
                        () -> {
                            log.info("Logging out");
                            tdLibClient.logout();
                        })
                .thenReturn(ResponseEntity.accepted().build());
    }
}
