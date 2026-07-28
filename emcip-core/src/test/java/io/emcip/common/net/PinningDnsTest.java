package io.emcip.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import okhttp3.Dns;
import org.junit.jupiter.api.Test;

class PinningDnsTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    private static Dns fixedResolver(List<InetAddress> addrs) {
        return hostname -> addrs;
    }

    @Test
    void returnsResolvedWhenPublic() throws Exception {
        List<InetAddress> addrs = List.of(ip("8.8.8.8"));
        PinningDns dns = new PinningDns(new SsrfGuard(SsrfAllowList.empty()), fixedResolver(addrs));
        assertThat(dns.lookup("good.example.com")).isEqualTo(addrs);
    }

    @Test
    void throwsWhenResolvedPrivate() throws Exception {
        Dns resolver = fixedResolver(List.of(ip("127.0.0.1")));
        PinningDns dns = new PinningDns(new SsrfGuard(SsrfAllowList.empty()), resolver);
        assertThatThrownBy(() -> dns.lookup("evil.example.com"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void throwsWhenAnyResolvedPrivate() throws Exception {
        Dns resolver = fixedResolver(List.of(ip("8.8.8.8"), ip("10.0.0.1")));
        PinningDns dns = new PinningDns(new SsrfGuard(SsrfAllowList.empty()), resolver);
        assertThatThrownBy(() -> dns.lookup("mixed.example.com"))
                .isInstanceOf(SsrfBlockedException.class);
    }
}
