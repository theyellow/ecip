package io.emcip.admin.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientIpTest {

    private ClientIp clientIp;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        clientIp = new ClientIp(2, meterRegistry);
    }

    @Test
    void picksEntryAtConfiguredHopFromTheRight() {
        MockServerHttpRequest req =
                MockServerHttpRequest.get("/api/auth/token")
                        .header("X-Forwarded-For", "9.9.9.9, 203.0.113.7, 10.42.0.5")
                        .build();

        ClientIp.Resolved r = clientIp.resolve(req);

        assertThat(r.ip()).isEqualTo("203.0.113.7");
        assertThat(r.source()).isEqualTo("XFF_TRUSTED");
    }

    @Test
    void spoofedLeftmostEntriesDoNotChangeTheResult() {
        MockServerHttpRequest spoofed =
                MockServerHttpRequest.get("/api/auth/token")
                        .header(
                                "X-Forwarded-For",
                                "1.1.1.1, 2.2.2.2, 3.3.3.3, 203.0.113.7, 10.42.0.5")
                        .build();

        assertThat(clientIp.resolve(spoofed).ip()).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToSocketWhenFewerEntriesThanHops() {
        MockServerHttpRequest req =
                MockServerHttpRequest.get("/api/auth/token")
                        .header("X-Forwarded-For", "10.42.0.5")
                        .remoteAddress(new java.net.InetSocketAddress("192.168.1.9", 1234))
                        .build();

        ClientIp.Resolved r = clientIp.resolve(req);

        assertThat(r.ip()).isEqualTo("192.168.1.9");
        assertThat(r.source()).isEqualTo("SOCKET");
        assertThat(
                        meterRegistry
                                .counter("emcip.ratelimit.untrusted_ip", "reason", "too_few_hops")
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void fallsBackWhenSelectedEntryIsNotAnIpAddress() {
        MockServerHttpRequest req =
                MockServerHttpRequest.get("/api/auth/token")
                        .header("X-Forwarded-For", "not-an-ip, 10.42.0.5")
                        .remoteAddress(new java.net.InetSocketAddress("192.168.1.9", 1234))
                        .build();

        ClientIp.Resolved r = clientIp.resolve(req);

        assertThat(r.ip()).isEqualTo("192.168.1.9");
        assertThat(r.source()).isEqualTo("SOCKET");
        assertThat(
                        meterRegistry
                                .counter("emcip.ratelimit.untrusted_ip", "reason", "malformed")
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void noHeaderAtAllFallsBackToSocket() {
        MockServerHttpRequest req =
                MockServerHttpRequest.get("/api/auth/token")
                        .remoteAddress(new java.net.InetSocketAddress("192.168.1.9", 1234))
                        .build();

        assertThat(clientIp.resolve(req).source()).isEqualTo("SOCKET");
    }

    @Test
    void unknownWhenNeitherHeaderNorSocketAvailable() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/api/auth/token").build();

        ClientIp.Resolved r = clientIp.resolve(req);

        assertThat(r.ip()).isEqualTo("unknown");
        assertThat(r.source()).isEqualTo("UNKNOWN");
    }

    @Test
    void isIpAddressRecognizesLiteralIpv4() {
        assertThat(ClientIp.isIpAddress("1.2.3.4")).isTrue();
    }

    @Test
    void isIpAddressRecognizesLiteralIpv6Loopback() {
        assertThat(ClientIp.isIpAddress("::1")).isTrue();
    }

    @Test
    void isIpAddressRecognizesLiteralIpv6Compressed() {
        assertThat(ClientIp.isIpAddress("2001:db8::1")).isTrue();
    }

    @Test
    void isIpAddressRejectsHostname() {
        assertThat(ClientIp.isIpAddress("not-an-ip")).isFalse();
    }

    @Test
    void isIpAddressRejectsOutOfRangeOctet() {
        assertThat(ClientIp.isIpAddress("999.1.1.1")).isFalse();
    }

    @Test
    void isIpAddressRejectsEmptyString() {
        assertThat(ClientIp.isIpAddress("")).isFalse();
    }
}
