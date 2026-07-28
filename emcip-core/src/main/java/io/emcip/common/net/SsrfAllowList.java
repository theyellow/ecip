package io.emcip.common.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Operator-configured allow-list of hostnames and CIDRs that bypass the SSRF deny set. Immutable.
 */
public final class SsrfAllowList {

    private static final SsrfAllowList EMPTY = new SsrfAllowList(Set.of(), List.of());

    private final Set<String> allowedHosts; // lower-cased exact hostnames
    private final List<CidrBlock> allowedCidrs;

    private SsrfAllowList(Set<String> allowedHosts, List<CidrBlock> allowedCidrs) {
        this.allowedHosts = allowedHosts;
        this.allowedCidrs = allowedCidrs;
    }

    public static SsrfAllowList empty() {
        return EMPTY;
    }

    public static SsrfAllowList parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }
        Set<String> hosts = new HashSet<>();
        List<CidrBlock> cidrs = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            if (entry.contains("/")) {
                cidrs.add(CidrBlock.parse(entry));
            } else {
                hosts.add(entry.toLowerCase(Locale.ROOT));
            }
        }
        return new SsrfAllowList(hosts, cidrs);
    }

    public boolean permits(String host, InetAddress ip) {
        if (host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (CidrBlock cidr : allowedCidrs) {
            if (cidr.contains(ip)) {
                return true;
            }
        }
        return false;
    }
}
