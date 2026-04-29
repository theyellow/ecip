package io.emcip.tdlib.adapter.controller;

import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.config.TdLibClientManager;
import io.emcip.tdlib.adapter.model.AuthRequest;
import io.emcip.tdlib.adapter.model.AuthStatusResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TdLibClientManager manager;

    /**
     * Called by admin-api on reconnect or startup session resume. Creates a TdLibClient for the
     * account. If sessionString is provided, TDLib will attempt silent resume.
     */
    @PostMapping("/{accountId}/initialize")
    public Mono<ResponseEntity<Void>> initialize(
            @PathVariable("accountId") UUID accountId, @RequestBody AuthRequest.Initialize req) {
        return Mono.fromRunnable(
                        () -> {
                            log.info("[{}] Initializing TdLibClient", accountId);
                            manager.createAndInitialize(
                                    accountId,
                                    req.apiId(),
                                    req.apiHash(),
                                    req.phoneNumber(),
                                    req.sessionString());
                        })
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @GetMapping("/{accountId}/status")
    public Mono<ResponseEntity<AuthStatusResponse>> getStatus(
            @PathVariable("accountId") UUID accountId) {
        if (!manager.hasClient(accountId)) {
            return Mono.just(ResponseEntity.ok(new AuthStatusResponse("UNCONFIGURED", null)));
        }
        TdLibClient client = manager.getClient(accountId);
        String status =
                client.isAuthorized()
                        ? "ACTIVE"
                        : client.isInitialized() ? "AWAITING_CODE" : "DISCONNECTED";
        return Mono.just(ResponseEntity.ok(new AuthStatusResponse(status, client.getLastError())));
    }

    @PostMapping("/{accountId}/phone")
    public Mono<ResponseEntity<Void>> setPhoneNumber(
            @PathVariable("accountId") UUID accountId, @RequestBody AuthRequest.PhoneNumber req) {
        return Mono.fromRunnable(
                        () -> manager.getClient(accountId).setPhoneNumber(req.phoneNumber()))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @PostMapping("/{accountId}/code")
    public Mono<ResponseEntity<Void>> setCode(
            @PathVariable("accountId") UUID accountId, @RequestBody AuthRequest.Code req) {
        return Mono.fromRunnable(
                        () -> manager.getClient(accountId).setAuthenticationCode(req.code()))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @PostMapping("/{accountId}/password")
    public Mono<ResponseEntity<Void>> setPassword(
            @PathVariable("accountId") UUID accountId, @RequestBody AuthRequest.Password req) {
        return Mono.fromRunnable(() -> manager.getClient(accountId).setPassword(req.password()))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @PostMapping("/{accountId}/logout")
    public Mono<ResponseEntity<Void>> logout(@PathVariable("accountId") UUID accountId) {
        return Mono.fromRunnable(
                        () -> {
                            manager.getClient(accountId).logout();
                            manager.removeClient(accountId);
                        })
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }
}
