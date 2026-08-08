package io.emcip.adminui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiProxyControllerForwardedHeaderTest {

    @Test
    void appendsOwnHopToInboundForwardedForChain() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.setRemoteAddr("10.42.0.5");

        String result = ApiProxyController.forwardedForHeader(request);

        assertThat(result).isEqualTo("203.0.113.7, 10.42.0.5");
    }

    @Test
    void setsOwnAddressWhenNoInboundChain() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        request.setRemoteAddr("10.42.0.5");

        assertThat(ApiProxyController.forwardedForHeader(request)).isEqualTo("10.42.0.5");
    }
}
