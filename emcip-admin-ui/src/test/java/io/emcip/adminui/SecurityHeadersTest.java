package io.emcip.adminui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// admin.api.url points at a refused port so the proxy path fails fast and deterministically;
// the security filter still writes headers regardless of the proxy outcome.
@SpringBootTest(properties = "admin.api.url=http://localhost:1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersTest {

    private static final String EXPECTED_CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src"
                    + " 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';"
                    + " base-uri 'self'; form-action 'self'; object-src 'none'";

    @Autowired private MockMvc mockMvc;

    @Test
    void allSecurityHeadersPresentOnActuator() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string("Content-Security-Policy", EXPECTED_CSP))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(
                        header().string(
                                        "Permissions-Policy",
                                        "geolocation=(), camera=(), microphone=(), payment=()"));
    }

    @Test
    void securityHeadersPresentOnProxyPath() throws Exception {
        mockMvc.perform(get("/api/anything"))
                .andExpect(header().string("Content-Security-Policy", EXPECTED_CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void scriptSrcIsStrict() throws Exception {
        String csp =
                mockMvc.perform(get("/actuator/health"))
                        .andReturn()
                        .getResponse()
                        .getHeader("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self';");
        assertThat(csp).doesNotContain("'unsafe-eval'");
        assertThat(csp).doesNotContain("script-src 'self' 'unsafe-inline'");
    }

    @Test
    void hstsEmittedOnSecureRequest() throws Exception {
        String hsts =
                mockMvc.perform(get("/actuator/health").secure(true))
                        .andReturn()
                        .getResponse()
                        .getHeader("Strict-Transport-Security");
        assertThat(hsts).contains("max-age=31536000");
        assertThat(hsts).contains("includeSubDomains");
    }

    @Test
    void noAuthChallenge() throws Exception {
        // permitAll: actuator health is reachable without any 401/redirect.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk());
    }
}
