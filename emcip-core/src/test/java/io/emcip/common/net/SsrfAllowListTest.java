package io.emcip.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class SsrfAllowListTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void emptyAllowsNothing() throws Exception {
        SsrfAllowList list = SsrfAllowList.empty();
        assertThat(list.permits("anything", ip("10.0.0.1"))).isFalse();
    }

    @Test
    void permitsByExactHostname() throws Exception {
        SsrfAllowList list = SsrfAllowList.parse(List.of("wiki.internal.example.com"));
        assertThat(list.permits("wiki.internal.example.com", ip("10.0.0.1"))).isTrue();
        assertThat(list.permits("WIKI.INTERNAL.EXAMPLE.COM", ip("10.0.0.1"))).isTrue();
        assertThat(list.permits("other.example.com", ip("10.0.0.1"))).isFalse();
    }

    @Test
    void permitsByCidr() throws Exception {
        SsrfAllowList list = SsrfAllowList.parse(List.of("10.20.0.0/24"));
        assertThat(list.permits("whatever", ip("10.20.0.5"))).isTrue();
        assertThat(list.permits("whatever", ip("10.21.0.5"))).isFalse();
    }

    @Test
    void mixedEntries() throws Exception {
        SsrfAllowList list =
                SsrfAllowList.parse(List.of("wiki.internal.example.com", "192.168.5.0/24"));
        assertThat(list.permits("wiki.internal.example.com", ip("8.8.8.8"))).isTrue();
        assertThat(list.permits("nope", ip("192.168.5.9"))).isTrue();
        assertThat(list.permits("nope", ip("192.168.6.9"))).isFalse();
    }
}
