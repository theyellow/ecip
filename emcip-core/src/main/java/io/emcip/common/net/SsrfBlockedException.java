package io.emcip.common.net;

import java.net.UnknownHostException;

/**
 * Thrown when a resolved address falls in an SSRF-blocked range. Extends {@link
 * UnknownHostException} so it can be thrown from {@code okhttp3.Dns#lookup}. The message names the
 * blocked target class only — never the raw internal response.
 */
public class SsrfBlockedException extends UnknownHostException {
    public SsrfBlockedException(String message) {
        super(message);
    }
}
