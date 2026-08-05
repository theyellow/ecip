package io.emcip.admin.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientIpTest {

    @Test
    void xffPresent_usesLeftmostToken() {
        var req =
                MockServerHttpRequest.get("/api/auth/token")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                        .build();
        ClientIp.Resolved r = ClientIp.resolve(req);
        assertThat(r.ip()).isEqualTo("203.0.113.7");
        assertThat(r.source()).isEqualTo("XFF");
    }

    @Test
    void noXff_usesSocketRemoteAddress() {
        var req =
                MockServerHttpRequest.get("/api/auth/token")
                        .remoteAddress(new InetSocketAddress("198.51.100.4", 44444))
                        .build();
        ClientIp.Resolved r = ClientIp.resolve(req);
        assertThat(r.ip()).isEqualTo("198.51.100.4");
        assertThat(r.source()).isEqualTo("SOCKET");
    }

    @Test
    void nothingAvailable_unknown() {
        var req = MockServerHttpRequest.get("/api/auth/token").build();
        ClientIp.Resolved r = ClientIp.resolve(req);
        assertThat(r.ip()).isEqualTo("unknown");
        assertThat(r.source()).isEqualTo("UNKNOWN");
    }
}
