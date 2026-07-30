package io.emcip.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard(SsrfAllowList.empty());

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void blocksLoopbackAndPrivateAndMetadata() throws Exception {
        assertThat(guard.isBlocked(ip("127.0.0.1"))).isTrue();
        assertThat(guard.isBlocked(ip("::1"))).isTrue();
        assertThat(guard.isBlocked(ip("10.1.2.3"))).isTrue();
        assertThat(guard.isBlocked(ip("172.16.5.4"))).isTrue();
        assertThat(guard.isBlocked(ip("192.168.1.1"))).isTrue();
        assertThat(guard.isBlocked(ip("169.254.169.254"))).isTrue();
        assertThat(guard.isBlocked(ip("fe80::1"))).isTrue();
        assertThat(guard.isBlocked(ip("0.0.0.0"))).isTrue();
        assertThat(guard.isBlocked(ip("224.0.0.1"))).isTrue();
    }

    @Test
    void blocksCarrierGradeNatIncludingAlibabaMetadata() throws Exception {
        // RFC-6598 100.64.0.0/10 — Alibaba Cloud's metadata service lives at 100.100.100.200.
        assertThat(guard.isBlocked(ip("100.100.100.200"))).isTrue();
        assertThat(guard.isBlocked(ip("100.64.0.1"))).isTrue();
        assertThat(guard.isBlocked(ip("100.127.255.254"))).isTrue();
    }

    @Test
    void blocksIpv4MappedIpv6Loopback() throws Exception {
        // ::ffff:127.0.0.1 must be classified as loopback even if it arrives as an Inet6Address.
        InetAddress mapped =
                InetAddress.getByAddress(
                        new byte[] {
                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1
                        });
        assertThat(guard.isBlocked(mapped)).isTrue();
    }

    @Test
    void allowsPublicAddresses() throws Exception {
        assertThat(guard.isBlocked(ip("8.8.8.8"))).isFalse();
        assertThat(guard.isBlocked(ip("2001:4860:4860::8888"))).isFalse();
    }

    @Test
    void validateRejectsWhenAnyResolvedAddressBlocked() throws Exception {
        assertThatThrownBy(
                        () ->
                                guard.validate(
                                        "evil.example.com",
                                        List.of(ip("8.8.8.8"), ip("127.0.0.1"))))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validateReturnsResolvedWhenAllPublic() throws Exception {
        List<InetAddress> resolved = List.of(ip("8.8.8.8"), ip("1.1.1.1"));
        assertThat(guard.validate("good.example.com", resolved)).isEqualTo(resolved);
    }

    @Test
    void validateMessageNamesBlockedClassLoopback() throws Exception {
        assertThatThrownBy(() -> guard.validate("evil.example.com", List.of(ip("127.0.0.1"))))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void validateMessageNamesBlockedClassMetadata() throws Exception {
        assertThatThrownBy(() -> guard.validate("evil.example.com", List.of(ip("169.254.169.254"))))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("link-local/metadata");
    }

    @Test
    void allowListOverridesBlockForHost() throws Exception {
        SsrfGuard allowing =
                new SsrfGuard(SsrfAllowList.parse(List.of("wiki.internal.example.com")));
        List<InetAddress> resolved = List.of(ip("10.0.0.5"));
        assertThat(allowing.validate("wiki.internal.example.com", resolved)).isEqualTo(resolved);
    }
}
