package io.emcip.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class CidrBlockTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void matchesIpv4WithinRange() throws Exception {
        CidrBlock block = CidrBlock.parse("10.0.0.0/8");
        assertThat(block.contains(ip("10.1.2.3"))).isTrue();
        assertThat(block.contains(ip("11.0.0.1"))).isFalse();
    }

    @Test
    void matchesExactHostRoute() throws Exception {
        CidrBlock block = CidrBlock.parse("169.254.169.254/32");
        assertThat(block.contains(ip("169.254.169.254"))).isTrue();
        assertThat(block.contains(ip("169.254.169.253"))).isFalse();
    }

    @Test
    void matchesIpv6Range() throws Exception {
        CidrBlock block = CidrBlock.parse("fe80::/10");
        assertThat(block.contains(ip("fe80::1"))).isTrue();
        assertThat(block.contains(ip("2001:db8::1"))).isFalse();
    }

    @Test
    void ipv4BlockDoesNotMatchIpv6Address() throws Exception {
        CidrBlock block = CidrBlock.parse("10.0.0.0/8");
        assertThat(block.contains(ip("::1"))).isFalse();
    }

    @Test
    void rejectsMalformed() {
        assertThatThrownBy(() -> CidrBlock.parse("nonsense"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/99"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
