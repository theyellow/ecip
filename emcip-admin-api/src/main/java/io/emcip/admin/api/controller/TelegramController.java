package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.TelegramConfig;
import io.emcip.admin.api.repository.TelegramConfigRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/telegram")
public class TelegramController {

    private static final long CONFIG_ID = 1L;

    private final TelegramConfigRepository configRepository;
    private final WebClient tdlibClient;

    public TelegramController(
            TelegramConfigRepository configRepository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient) {
        this.configRepository = configRepository;
        this.tdlibClient = tdlibClient;
    }

    /** GET /api/telegram/config — return stored credentials (session_string masked). */
    @GetMapping("/config")
    public Mono<Map<String, Object>> getConfig() {
        return configRepository
                .findById(CONFIG_ID)
                .map(TelegramController::toConfigMap)
                .defaultIfEmpty(emptyConfigMap());
    }

    private static Map<String, Object> toConfigMap(TelegramConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phoneNumber", cfg.getPhoneNumber() != null ? cfg.getPhoneNumber() : "");
        m.put("apiId", cfg.getApiId() != null ? cfg.getApiId() : 0);
        m.put("apiHash", cfg.getApiHash() != null ? cfg.getApiHash() : "");
        m.put(
                "sessionStringSet",
                cfg.getSessionString() != null && !cfg.getSessionString().isEmpty());
        return m;
    }

    private static Map<String, Object> emptyConfigMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phoneNumber", "");
        m.put("apiId", 0);
        m.put("apiHash", "");
        m.put("sessionStringSet", false);
        return m;
    }

    /** PUT /api/telegram/config — save credentials. */
    @PutMapping("/config")
    public Mono<Map<String, Object>> saveConfig(@RequestBody TelegramConfigRequest req) {
        return configRepository
                .findById(CONFIG_ID)
                .switchIfEmpty(Mono.just(TelegramConfig.builder().id(CONFIG_ID).build()))
                .flatMap(
                        cfg -> {
                            if (req.getPhoneNumber() != null)
                                cfg.setPhoneNumber(req.getPhoneNumber());
                            if (req.getApiId() != null) cfg.setApiId(req.getApiId());
                            if (req.getApiHash() != null) cfg.setApiHash(req.getApiHash());
                            if (req.getSessionString() != null && !req.getSessionString().isEmpty())
                                cfg.setSessionString(req.getSessionString());
                            cfg.setUpdatedAt(Instant.now());
                            return configRepository.save(cfg);
                        })
                .map(
                        cfg -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("saved", true);
                            return m;
                        });
    }

    /**
     * GET /api/telegram/status — proxy to tdlib-adapter GET /api/auth/status. Also reads stored
     * phone number for display. Returns {status: "CONNECTED"|"PENDING"|"DISCONNECTED", message:
     * "...", phoneNumber: "..."}.
     */
    @GetMapping("/status")
    public Mono<Map<String, Object>> getStatus() {
        return configRepository
                .findById(CONFIG_ID)
                .map(cfg -> cfg.getPhoneNumber() != null ? cfg.getPhoneNumber() : "")
                .defaultIfEmpty("")
                .flatMap(
                        phone ->
                                tdlibClient
                                        .get()
                                        .uri("/api/auth/status")
                                        .retrieve()
                                        .bodyToMono(TdlibStatusResponse.class)
                                        .map(
                                                r -> {
                                                    String status =
                                                            r.isAuthorized()
                                                                    ? "CONNECTED"
                                                                    : r.isInitialized()
                                                                            ? "PENDING"
                                                                            : "DISCONNECTED";
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("status", status);
                                                    m.put("message", r.getMessage());
                                                    m.put("phoneNumber", phone);
                                                    return m;
                                                })
                                        .onErrorResume(
                                                e -> {
                                                    log.warn(
                                                            "tdlib-adapter unreachable: {}",
                                                            e.getMessage());
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("status", "DISCONNECTED");
                                                    m.put("message", "Adapter offline");
                                                    m.put("phoneNumber", phone);
                                                    return Mono.just(m);
                                                }));
    }

    /**
     * POST /api/telegram/reconnect — trigger re-authentication by sending stored phone number to
     * tdlib-adapter.
     */
    @PostMapping("/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> reconnect() {
        return configRepository
                .findById(CONFIG_ID)
                .flatMap(
                        cfg -> {
                            if (cfg.getPhoneNumber() == null || cfg.getPhoneNumber().isEmpty()) {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("accepted", false);
                                m.put("reason", "No phone number configured");
                                return Mono.<Map<String, Object>>just(m);
                            }
                            final String phone = cfg.getPhoneNumber();
                            Map<String, Object> successResult = new LinkedHashMap<>();
                            successResult.put("accepted", true);
                            successResult.put("phone", phone);
                            return tdlibClient
                                    .post()
                                    .uri("/api/auth/phone")
                                    .bodyValue(Map.of("phoneNumber", phone))
                                    .retrieve()
                                    .bodyToMono(Void.class)
                                    .thenReturn(successResult)
                                    .onErrorResume(
                                            e -> {
                                                log.warn("reconnect failed: {}", e.getMessage());
                                                Map<String, Object> m = new LinkedHashMap<>();
                                                m.put("accepted", false);
                                                m.put("reason", e.getMessage());
                                                return Mono.just(m);
                                            });
                        })
                .switchIfEmpty(Mono.just(noConfigResult()));
    }

    private static Map<String, Object> noConfigResult() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", false);
        m.put("reason", "No config found");
        return m;
    }

    @Data
    public static class TelegramConfigRequest {
        private String phoneNumber;
        private Integer apiId;
        private String apiHash;
        private String sessionString;
    }

    @Data
    public static class TdlibStatusResponse {
        private boolean initialized;
        private boolean authorized;
        private String message;
    }
}
