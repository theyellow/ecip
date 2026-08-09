package io.emcip.admin.api.config;

import io.emcip.admin.api.audit.AdminAuditPublisher;
import io.emcip.common.crypto.PlaintextSecretException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.security.Principal;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AdminAuditPublisher auditPublisher;

    /**
     * Authorization denials must be 403, and must be audited.
     *
     * <p>Without this handler the catch-all {@code @ExceptionHandler(Exception.class)} below
     * claimed every {@link AccessDeniedException} and returned <b>500 "An unexpected error
     * occurred"</b>. A {@code @PreAuthorize} denial is raised while the controller method is
     * invoked, i.e. inside {@code DispatcherHandler} — closer to the controller than Spring
     * Security's {@code ExceptionTranslationWebFilter}, so this advice sees it first and the
     * filter's {@code accessDeniedHandler} in {@link io.emcip.admin.api.security.SecurityConfig}
     * never ran.
     *
     * <p>The filter's handler still covers denials raised by the {@code authorizeExchange} path
     * rules (for example {@code /api/internal/**}), which never reach a controller.
     *
     * <p>Because the filter's handler is bypassed here, this advice also publishes the {@code
     * ACCESS_DENIED} audit event for method-level denials (AUDIT-DENY). Without it the P2.8 admin
     * audit trail recorded only path-rule denials — the small minority — and silently missed every
     * {@code @PreAuthorize} denial. The two paths are mutually exclusive, so a denial is audited
     * exactly once: a path-rule denial never reaches a controller and therefore never reaches this
     * advice.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleAccessDenied(
            AccessDeniedException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("anonymous")
                .map(
                        actor -> {
                            log.warn(
                                    "Access denied for {} on {}: {}", actor, path, ex.getMessage());
                            auditPublisher.publish(
                                    "ACCESS_DENIED",
                                    "Endpoint",
                                    path,
                                    actor,
                                    null,
                                    Map.of(
                                            "reason",
                                            ex.getMessage() != null
                                                    ? ex.getMessage()
                                                    : "Access denied"),
                                    "DENIED");
                            ProblemDetail problem =
                                    ProblemDetail.forStatusAndDetail(
                                            HttpStatus.FORBIDDEN, "Access denied");
                            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
                        });
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleValidation(WebExchangeBindException ex) {
        Map<String, String> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .collect(
                                Collectors.toMap(
                                        FieldError::getField,
                                        fe ->
                                                fe.getDefaultMessage() != null
                                                        ? fe.getDefaultMessage()
                                                        : "invalid",
                                        (a, b) -> a));
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", errors);
        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(problem));
    }

    /**
     * A stored secret predating encryption. Mapped centrally so every endpoint reading any of the
     * encrypted columns behaves the same, rather than each controller inventing its own wording.
     *
     * <p>409 rather than 500: the request was well-formed and the server is healthy — the stored
     * state is what conflicts, and the operator can resolve it by re-entering the value.
     *
     * <p>The exception message names a table, a column and a repo path; none of that reaches the
     * client. Only a stable {@code code} and a logical field label are exposed, with the detail
     * logged. Registered above {@link #handleIllegalArgument} because {@code
     * PlaintextSecretException} extends {@code IllegalStateException}, not {@code
     * IllegalArgumentException} — but ordering is stated explicitly here so a future reshuffle does
     * not silently drop it into the catch-all and start returning 500s.
     */
    @ExceptionHandler(PlaintextSecretException.class)
    public Mono<ResponseEntity<ProblemDetail>> handlePlaintextSecret(PlaintextSecretException ex) {
        log.error("Unencrypted secret encountered at {}", ex.getLocation(), ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        "This value was stored before secrets encryption was enabled. Re-enter "
                                + safeFieldLabel(ex.getLocation())
                                + " to secure it.");
        problem.setTitle("Secret not encrypted");
        problem.setProperty("code", "SECRET_NOT_ENCRYPTED");
        problem.setProperty("field", safeFieldLabel(ex.getLocation()));
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(problem));
    }

    /**
     * Maps {@code table.column} to wording an operator recognises from the UI. Unknown locations
     * fall back to a generic phrase rather than passing the schema name through — a column added
     * later must not start leaking just because nobody updated this switch.
     */
    private static String safeFieldLabel(String location) {
        return switch (location) {
            case "telegram_accounts.api_hash" -> "the Telegram API hash";
            case "telegram_accounts.session_string" -> "the Telegram session";
            case "ke_vendor_api_keys.api_key" -> "the vendor API key";
            case "llm_provider_configs.api_key" -> "the LLM provider API key";
            default -> "this secret";
        };
    }

    /**
     * 400 without echoing the exception text.
     *
     * <p>No admin-api code throws {@code IllegalArgumentException} to carry a user-facing message —
     * the only explicit throws are startup config validation. So everything arriving here at
     * request time comes from library code, whose messages describe internals:
     *
     * <ul>
     *   <li>{@code Enum.valueOf} → "No enum constant io.emcip.admin.api.entity.Xxx.BAD", disclosing
     *       package and class names. Reachable via {@code TelegramAccountStatus.valueOf} on a
     *       response from tdlib-adapter.
     *   <li>{@code UUID.fromString} → "Invalid UUID string: …", reachable from a malformed {@code
     *       X-Tenant-Id} header.
     * </ul>
     *
     * <p>Intentional user-facing messages have two supported routes that are unaffected: bean
     * validation ({@code @Valid} → {@link #handleValidation}, which returns per-field errors) and
     * {@code ResponseStatusException(status, reason)}, whose reason is author-written. If you want
     * a client to read a specific sentence, throw one of those — not a bare {@code
     * IllegalArgumentException}.
     *
     * <p>Logged at WARN with the stack trace rather than DEBUG: the detail has to survive
     * somewhere, and this is now the only place it exists.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public Mono<ResponseEntity<ProblemDetail>> handleRateLimitExceeded(RequestNotPermitted ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem));
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleCircuitOpen(CallNotPermittedException ex) {
        log.warn("Circuit breaker open: {}", ex.getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable");
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ProblemDetail>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        return Mono.just(ResponseEntity.internalServerError().body(problem));
    }
}
