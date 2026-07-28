package io.emcip.common.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Classifies resolved IP addresses against a hardcoded SSRF deny set (loopback, RFC-1918,
 * link-local/metadata, wildcard, multicast/reserved), honoring an operator {@link SsrfAllowList}.
 * Spring-free and side-effect-free.
 */
public final class SsrfGuard {

    private static final List<CidrBlock> DENY =
            List.of(
                    // loopback
                    CidrBlock.parse("127.0.0.0/8"),
                    CidrBlock.parse("::1/128"),
                    // RFC-1918 private
                    CidrBlock.parse("10.0.0.0/8"),
                    CidrBlock.parse("172.16.0.0/12"),
                    CidrBlock.parse("192.168.0.0/16"),
                    // link-local + cloud metadata (169.254.169.254)
                    CidrBlock.parse("169.254.0.0/16"),
                    CidrBlock.parse("fe80::/10"),
                    // IPv6 unique-local
                    CidrBlock.parse("fc00::/7"),
                    // wildcard / "this host"
                    CidrBlock.parse("0.0.0.0/8"),
                    CidrBlock.parse("::/128"),
                    // multicast + reserved
                    CidrBlock.parse("224.0.0.0/4"),
                    CidrBlock.parse("240.0.0.0/4"),
                    CidrBlock.parse("ff00::/8"));

    private final SsrfAllowList allowList;

    public SsrfGuard(SsrfAllowList allowList) {
        this.allowList = allowList;
    }

    /** True if the address is in a blocked range (IPv4-mapped IPv6 is unwrapped first). */
    public boolean isBlocked(InetAddress ip) {
        InetAddress effective = unwrapIpv4Mapped(ip);
        for (CidrBlock block : DENY) {
            if (block.contains(effective)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reject-if-any-blocked. Returns {@code resolved} unchanged when every address is safe (or
     * allow-listed); otherwise throws.
     */
    public List<InetAddress> validate(String host, List<InetAddress> resolved)
            throws SsrfBlockedException {
        for (InetAddress ip : resolved) {
            if (isBlocked(ip) && !allowList.permits(host, ip)) {
                throw new SsrfBlockedException(
                        "SSRF blocked: host '" + host + "' resolves to a disallowed address range");
            }
        }
        return resolved;
    }

    private static InetAddress unwrapIpv4Mapped(InetAddress ip) {
        if (!(ip instanceof Inet6Address)) {
            return ip;
        }
        byte[] b = ip.getAddress();
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                mapped = false;
                break;
            }
        }
        if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            try {
                return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
            } catch (UnknownHostException e) {
                return ip; // 4-byte array is always valid; unreachable
            }
        }
        return ip;
    }
}
