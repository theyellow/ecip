package io.emcip.adminui;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Emits security response headers for the admin-ui BFF. The chain authenticates nothing ({@code
 * permitAll}) — JWT auth lives in admin-api; this exists only to write CSP/HSTS/frame options onto
 * every response.
 */
@Configuration
public class SecurityConfig {

    private static final String CSP_POLICY =
            "default-src 'self'; "
                    + "script-src 'self'; "
                    // style-src carries 'unsafe-inline' only because the React frontend still
                    // uses inline style={{}} attributes. Interim state — follow-up RT2-007-F1
                    // removes those and tightens this to strict 'self'. script-src stays strict
                    // 'self' (no such concession).
                    + "style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; "
                    + "font-src 'self'; "
                    + "connect-src 'self'; "
                    + "frame-ancestors 'none'; "
                    + "base-uri 'self'; "
                    + "form-action 'self'; "
                    + "object-src 'none'";

    private static final String PERMISSIONS_POLICY =
            "geolocation=(), camera=(), microphone=(), payment=()";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // CSRF is intentionally disabled: admin-ui is a stateless, cookieless BFF. It holds
                // no server-side session and sets no auth cookie — the browser authenticates every
                // request with a Bearer JWT read from sessionStorage and sent as an explicit
                // Authorization header. CSRF is only exploitable when the browser carries an
                // ambient
                // credential (cookie/session) that a cross-site request can ride; there is none
                // here,
                // and a cross-origin page can neither read sessionStorage nor forge the
                // Authorization
                // header. Leaving CSRF enabled would instead break every proxied POST/PUT/DELETE to
                // admin-api (which is itself the JWT auth boundary). CodeQL flags this line
                // (java/spring-disabled-csrf-protection) — it is a reviewed false positive for a
                // token-based, cookieless API; the alert is dismissed with this justification.
                .csrf(AbstractHttpConfigurer::disable)
                .headers(
                        headers ->
                                // Spring Security's default Cache-Control: no-cache, no-store,
                                // max-age=0, must-revalidate (plus Pragma/Expires) is left
                                // enabled on every response, including static SPA assets. This
                                // is deliberate for this admin BFF: sensitive admin content must
                                // not cache to disk (OWASP-aligned). Do not re-enable caching.
                                headers.contentSecurityPolicy(
                                                csp -> csp.policyDirectives(CSP_POLICY))
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31_536_000))
                                        .frameOptions(frame -> frame.deny())
                                        .contentTypeOptions(Customizer.withDefaults())
                                        .referrerPolicy(
                                                rp ->
                                                        rp.policy(
                                                                ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .NO_REFERRER))
                                        .addHeaderWriter(
                                                new StaticHeadersWriter(
                                                        "Permissions-Policy", PERMISSIONS_POLICY)));
        return http.build();
    }
}
