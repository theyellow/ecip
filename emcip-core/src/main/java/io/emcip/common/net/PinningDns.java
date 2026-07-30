package io.emcip.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;

/**
 * An OkHttp {@link Dns} that validates every resolved address through {@link SsrfGuard} and returns
 * exactly the validated list, so OkHttp connects only to a pre-validated (pinned) IP. Closes the
 * DNS-rebinding TOCTOU because there is no second resolution between validation and connect.
 */
public final class PinningDns implements Dns {

    private final SsrfGuard guard;
    private final Dns systemDns;

    public PinningDns(SsrfGuard guard) {
        this(guard, Dns.SYSTEM);
    }

    public PinningDns(SsrfGuard guard, Dns systemDns) {
        this.guard = guard;
        this.systemDns = systemDns;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> resolved = systemDns.lookup(hostname);
        return guard.validate(hostname, resolved);
    }
}
