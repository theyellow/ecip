package io.emcip.admin.api.util;

import java.net.InetSocketAddress;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Best-effort client-IP resolution for audit records. XFF is only trustworthy behind a trusted
 * proxy (see BACKLOG P2.8-F1); the source tag records which path produced the value.
 */
public final class ClientIp {

    public record Resolved(String ip, String source) {}

    private ClientIp() {}

    public static Resolved resolve(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String leftmost = xff.split(",")[0].trim();
            if (!leftmost.isEmpty()) {
                return new Resolved(leftmost, "XFF");
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return new Resolved(remote.getAddress().getHostAddress(), "SOCKET");
        }
        return new Resolved("unknown", "UNKNOWN");
    }
}
