package io.emcip.common.net;

import java.time.Duration;
import okhttp3.OkHttpClient;

/**
 * Factory for the SSRF-guarded OkHttp client used to fetch user-submitted URLs. The returned client
 * defends every fetch on two layers:
 *
 * <ul>
 *   <li>a pre-connect application interceptor that runs {@link SsrfGuard#validateUrlHost} — this
 *       covers <b>IP-literal</b> hosts, which OkHttp connects to directly without invoking the DNS
 *       hook;
 *   <li>a {@link PinningDns} resolver that validates and pins the resolved address — this covers
 *       <b>hostname</b> hosts and closes the DNS-rebinding TOCTOU.
 * </ul>
 *
 * Redirects are disabled so no hop can escape validation. This factory is the single source of
 * truth for how the guarded client is built, so production wiring and tests cannot drift apart.
 */
public final class SsrfHttpClients {

    private SsrfHttpClients() {}

    /**
     * Build a guarded client.
     *
     * @param allowList operator allow-list of hosts/CIDRs that bypass the private-range block
     * @param timeout applied to connect, read, and total-call durations
     */
    public static OkHttpClient create(SsrfAllowList allowList, Duration timeout) {
        SsrfGuard guard = new SsrfGuard(allowList);
        return new OkHttpClient.Builder()
                .dns(new PinningDns(guard))
                .addInterceptor(
                        chain -> {
                            guard.validateUrlHost(chain.request().url().host());
                            return chain.proceed(chain.request());
                        })
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .callTimeout(timeout)
                .build();
    }
}
