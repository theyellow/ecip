package io.emcip.admin.api.security;

import io.emcip.admin.api.util.ClientIp;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Applies per-caller rate limits.
 *
 * <p>Registered after {@code SecurityWebFiltersOrder.AUTHENTICATION} so the principal is resolved —
 * the same placement {@link AdminTenantContextFilter} uses.
 *
 * <p>This filter writes its own 429. {@code GlobalExceptionHandler} is a
 * {@code @RestControllerAdvice} and cannot see exceptions thrown from a WebFilter, so throwing here
 * would surface as a 500 — the same defect AUTHZ-500 (P3.3) fixed for authorization denials.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitWebFilter implements WebFilter {

    private static final String RETRY_AFTER_SECONDS = "60";

    private final ClientIp clientIp;
    private final RateLimitGroups groups;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Optional<RateLimitGroups.Group> maybeGroup =
                groups.resolve(
                        exchange.getRequest().getMethod(), exchange.getRequest().getPath().value());
        if (maybeGroup.isEmpty()) {
            return chain.filter(exchange);
        }
        RateLimitGroups.Group group = maybeGroup.get();

        if (group.keyByIp()) {
            return apply(
                    exchange, chain, group, clientIp.resolve(exchange.getRequest()).ip(), "ip");
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .defaultIfEmpty(clientIp.resolve(exchange.getRequest()).ip())
                .flatMap(key -> apply(exchange, chain, group, key, "user"));
    }

    private Mono<Void> apply(
            ServerWebExchange exchange,
            WebFilterChain chain,
            RateLimitGroups.Group group,
            String key,
            String keyType) {
        RateLimiter limiter = groups.limiterFor(group, key);
        if (limiter.acquirePermission()) {
            return chain.filter(exchange);
        }
        return reject(exchange, group, key, keyType);
    }

    private Mono<Void> reject(
            ServerWebExchange exchange, RateLimitGroups.Group group, String key, String keyType) {
        meterRegistry
                .counter("emcip.ratelimit.rejected", "group", group.instanceName())
                .increment();

        // The key is logged only for the auth group: brute-force forensics need the IP, and P2.8
        // already records client IPs for login events. Elsewhere the key is a username.
        if (group == RateLimitGroups.Group.AUTH) {
            log.warn("Rate limit exceeded: group={} keyType={} key={}", group, keyType, key);
        } else {
            log.warn("Rate limit exceeded: group={} keyType={}", group, keyType);
        }

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After", RETRY_AFTER_SECONDS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        // Body shape matches GlobalExceptionHandler's RequestNotPermitted mapping (ProblemDetail
        // .forStatusAndDetail(TOO_MANY_REQUESTS, "Rate limit exceeded")) so clients see one format
        // regardless of which layer rejected them.
        String body =
                """
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"Rate limit exceeded"}""";
        DataBuffer buffer =
                exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
