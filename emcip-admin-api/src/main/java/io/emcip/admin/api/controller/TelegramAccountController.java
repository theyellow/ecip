package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.service.TelegramAccountService;
import io.emcip.common.tenant.ReactorTenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/telegram/accounts")
@Tag(
        name = "Telegram Accounts",
        description = "Manage Telegram account connections, authentication, and group watching")
@PreAuthorize("hasAuthority('TELEGRAM_READ')")
public class TelegramAccountController {

    private final TelegramAccountService telegramAccountService;

    @Operation(summary = "List all Telegram accounts")
    @GetMapping
    @PreAuthorize("hasAuthority('TELEGRAM_READ')")
    public Mono<List<Map<String, Object>>> listAccounts() {
        return telegramAccountService
                .findAll()
                .map(TelegramAccountController::toSafeMap)
                .collectList();
    }

    @Operation(summary = "Create and connect a new Telegram account")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Map<String, Object>> createAccount(@Valid @RequestBody CreateAccountRequest req) {
        if ((req.apiId() != null) != (req.apiHash() != null && !req.apiHash().isBlank())) {
            return Mono.error(
                    new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "apiId and apiHash must both be provided, or both omitted"));
        }
        return Mono.deferContextual(
                ctx -> {
                    UUID tenantId =
                            ReactorTenantContext.isAdminMode(ctx)
                                    ? null
                                    : UUID.fromString(ReactorTenantContext.getTenantId(ctx));
                    return telegramAccountService
                            .create(
                                    req.phoneNumber(),
                                    req.displayName(),
                                    tenantId,
                                    req.apiId(),
                                    req.apiHash())
                            .map(TelegramAccountController::toSafeMap);
                });
    }

    @Operation(summary = "Delete a Telegram account")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Void> deleteAccount(@PathVariable("id") UUID id) {
        return telegramAccountService.delete(id);
    }

    @Operation(summary = "Get connection status of a Telegram account")
    @GetMapping("/{id}/status")
    @PreAuthorize("hasAuthority('TELEGRAM_READ')")
    public Mono<Map<String, Object>> getStatus(@PathVariable("id") UUID id) {
        return telegramAccountService
                .getStatus(id)
                .map(
                        account -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", id.toString());
                            m.put("status", account.getStatus().name());
                            m.put("lastError", account.getLastError());
                            return m;
                        });
    }

    @Operation(summary = "Reconnect a disconnected Telegram account")
    @PostMapping("/{id}/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Map<String, Object>> reconnect(@PathVariable("id") UUID id) {
        return telegramAccountService
                .reconnect(id)
                .thenReturn(Map.<String, Object>of("accepted", true))
                .onErrorResume(
                        e -> {
                            log.warn("reconnect failed for {}: {}", id, e.getMessage());
                            return Mono.just(Map.of("accepted", false, "reason", e.getMessage()));
                        });
    }

    @Operation(summary = "Submit authentication code for a Telegram account")
    @PostMapping("/{id}/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Void> submitCode(@PathVariable("id") UUID id, @Valid @RequestBody CodeRequest req) {
        return telegramAccountService.submitCode(id, req.code());
    }

    @Operation(summary = "Submit 2FA password for a Telegram account")
    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Void> submitPassword(
            @PathVariable("id") UUID id, @Valid @RequestBody PasswordRequest req) {
        return telegramAccountService.submitPassword(id, req.password());
    }

    @Operation(summary = "Log out a Telegram account")
    @PostMapping("/{id}/logout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Void> logout(@PathVariable("id") UUID id) {
        return telegramAccountService.logout(id);
    }

    @Operation(summary = "Sync watched groups across all accounts")
    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Void> syncWatchedGroups() {
        return telegramAccountService.sync();
    }

    @Operation(summary = "Discover available Telegram chats for an account")
    @GetMapping("/{id}/chats")
    @PreAuthorize("hasAuthority('TELEGRAM_READ')")
    public Mono<List<Map<String, Object>>> discoverChats(@PathVariable("id") UUID id) {
        return telegramAccountService.discoverChats(id);
    }

    @Operation(summary = "List watched groups for an account")
    @GetMapping("/{id}/watched")
    @PreAuthorize("hasAuthority('TELEGRAM_READ')")
    public Mono<List<Map<String, Object>>> listWatched(@PathVariable("id") UUID id) {
        return telegramAccountService.findWatchedGroups(id).map(this::toWatchedMap).collectList();
    }

    @Operation(summary = "Start watching a Telegram group")
    @PostMapping("/{id}/watch")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Map<String, Object>> watchGroup(
            @PathVariable("id") UUID accountId, @Valid @RequestBody WatchRequest req) {
        return telegramAccountService
                .watchGroup(accountId, req.chatId(), req.title())
                .map(this::toWatchedMap);
    }

    @Operation(summary = "Stop watching a Telegram group")
    @DeleteMapping("/{id}/watch/{chatId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Void> unwatchGroup(
            @PathVariable("id") UUID accountId, @PathVariable("chatId") Long chatId) {
        return telegramAccountService.unwatchGroup(accountId, chatId);
    }

    private Map<String, Object> toWatchedMap(GroupProfile profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chatId", profile.getTelegramChatId());
        m.put("groupProfileId", profile.getId());
        m.put("name", profile.getName());
        m.put("moderationLevel", profile.getModerationLevel());
        return m;
    }

    private static Map<String, Object> toSafeMap(TelegramAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId().toString());
        m.put("displayName", a.getDisplayName() != null ? a.getDisplayName() : "");
        m.put("phoneNumber", a.getPhoneNumber());
        m.put("apiId", a.getApiId());
        m.put("status", a.getStatus().name());
        m.put("lastError", a.getLastError());
        m.put("sessionStringSet", a.getSessionString() != null && !a.getSessionString().isEmpty());
        m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        return m;
    }

    @Schema(description = "Request to register a new Telegram account")
    public record CreateAccountRequest(
            @NotBlank(message = "phoneNumber is required")
                    @Pattern(
                            regexp = "^\\+\\d{10,15}$",
                            message =
                                    "phoneNumber must be in international format, e.g."
                                            + " +491234567890")
                    @Schema(
                            description = "Phone number in international format",
                            example = "+491234567890")
                    String phoneNumber,
            @Size(max = 100, message = "displayName must be 100 characters or fewer")
                    @Schema(
                            description = "Human-readable label for this account",
                            example = "Main bot")
                    String displayName,
            @Schema(description = "Optional Telegram API ID (from my.telegram.org)") Integer apiId,
            @Schema(description = "Optional Telegram API Hash (from my.telegram.org)")
                    String apiHash) {}

    @Schema(description = "Telegram authentication code sent to the phone")
    public record CodeRequest(
            @NotBlank(message = "code is required")
                    @Pattern(regexp = "^\\d{4,7}$", message = "code must be 4–7 digits")
                    @Schema(
                            description = "Verification code received via Telegram",
                            example = "12345")
                    String code) {}

    @Schema(description = "Two-factor authentication password")
    public record PasswordRequest(
            @NotBlank(message = "password is required")
                    @Schema(description = "2FA password for the Telegram account")
                    String password) {}

    @Schema(description = "Request to start watching a Telegram group")
    public record WatchRequest(
            @Schema(description = "Telegram chat ID to watch", example = "-1001234567890")
                    long chatId,
            @Size(max = 255, message = "title must be 255 characters or fewer")
                    @Schema(description = "Display title for the group", example = "My Community")
                    String title) {}
}
