package io.emcip.admin.api.util;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the client IP from {@code X-Forwarded-For} by counting hops from the RIGHT.
 *
 * <p>The leftmost token is whatever the client sent, so keying a rate limiter on it lets an
 * attacker mint a fresh bucket per request. Counting from the right means attacker-prepended
 * entries land to the left of the trusted position and are ignored, however many are added.
 *
 * <p>{@code trusted-proxy-hops} must match the real proxy chain (ingress + BFF). If the header
 * carries fewer entries than that, this class does NOT guess — it falls back to the socket address
 * and increments a counter, so a topology change surfaces in metrics instead of silently selecting
 * an attacker-controlled value. See P3.6 / P2.8-F1.
 */
@Slf4j
@Component
public class ClientIp {

    public record Resolved(String ip, String source) {}

    private static final Resolved UNKNOWN = new Resolved("unknown", "UNKNOWN");

    // Loose but sufficient literal-address matchers: reject anything that needs DNS to resolve.
    // IPv4: four 1-3 digit groups separated by dots (numeric ranges validated separately below).
    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    // IPv6: hex groups separated by colons, allowing a single "::" compression. Deliberately
    // permissive (does not fully validate group count) - good enough to distinguish a real
    // literal from garbage like "not-an-ip" without needing a network call.
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+$");

    private final int trustedProxyHops;
    private final MeterRegistry meterRegistry;

    public ClientIp(
            @Value("${emcip.security.trusted-proxy-hops}") int trustedProxyHops,
            MeterRegistry meterRegistry) {
        if (trustedProxyHops < 1) {
            throw new IllegalArgumentException(
                    "emcip.security.trusted-proxy-hops must be >= 1, was " + trustedProxyHops);
        }
        this.trustedProxyHops = trustedProxyHops;
        this.meterRegistry = meterRegistry;
    }

    public Resolved resolve(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            String[] hops = xff.split(",");
            int index = hops.length - trustedProxyHops;
            if (index < 0) {
                return fallback(request, "too_few_hops");
            }
            String candidate = hops[index].trim();
            if (!isIpAddress(candidate)) {
                return fallback(request, "malformed");
            }
            return new Resolved(candidate, "XFF_TRUSTED");
        }
        return fallback(request, "no_xff");
    }

    private Resolved fallback(ServerHttpRequest request, String reason) {
        meterRegistry.counter("emcip.ratelimit.untrusted_ip", "reason", reason).increment();
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return new Resolved(remote.getAddress().getHostAddress(), "SOCKET");
        }
        return UNKNOWN;
    }

    /**
     * Recognizes IPv4 dotted-quad and IPv6 hex-colon literal addresses without performing DNS
     * resolution. {@code InetAddress.getByName} was deliberately avoided here: it resolves
     * hostnames over the network, which would turn parsing an attacker-controlled header into an
     * outbound network call.
     */
    static boolean isIpAddress(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        java.util.regex.Matcher ipv4 = IPV4.matcher(value);
        if (ipv4.matches()) {
            for (int i = 1; i <= 4; i++) {
                int octet = Integer.parseInt(ipv4.group(i));
                if (octet > 255) {
                    return false;
                }
            }
            return true;
        }
        return value.contains(":") && IPV6.matcher(value).matches();
    }
}
