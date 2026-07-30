package io.emcip.adminui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

// admin.api.url points at a refused port so the proxy path fails fast and deterministically;
// the security filter still writes headers regardless of the proxy outcome. RANDOM_PORT +
// TestRestTemplate exercises a real servlet container, so an unhandled downstream exception
// becomes a real 500 response carrying headers (matching production), instead of MockMvc's
// behavior of rethrowing exceptions unresolved by any HandlerExceptionResolver.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "admin.api.url=http://localhost:1")
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class SecurityHeadersTest {

    private static final String EXPECTED_CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src"
                    + " 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';"
                    + " base-uri 'self'; form-action 'self'; object-src 'none'";

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void allSecurityHeadersPresentOnActuator() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo(EXPECTED_CSP);
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("Permissions-Policy"))
                .isEqualTo("geolocation=(), camera=(), microphone=(), payment=()");
    }

    @Test
    void securityHeadersPresentOnProxyPath() {
        // Backend unreachable (admin.api.url=http://localhost:1) -> real 500, but the security
        // filter runs before the proxy controller, so headers are present regardless of status.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/anything", String.class);
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo(EXPECTED_CSP);
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void scriptSrcIsStrict() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);
        String csp = response.getHeaders().getFirst("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self';");
        assertThat(csp).doesNotContain("'unsafe-eval'");
        assertThat(csp).doesNotContain("script-src 'self' 'unsafe-inline'");
    }

    @Test
    void hstsEmittedOnSecureRequest() {
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.set("X-Forwarded-Proto", "https");
        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/actuator/health",
                        HttpMethod.GET,
                        new HttpEntity<>(requestHeaders),
                        String.class);
        String hsts = response.getHeaders().getFirst("Strict-Transport-Security");
        assertThat(hsts).contains("max-age=31536000");
        assertThat(hsts).contains("includeSubDomains");
    }

    @Test
    void noAuthChallenge() {
        // permitAll: actuator health is reachable without any 401/redirect.
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
