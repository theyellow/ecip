package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.reactive.server.WebTestClient;

class PrometheusScrapingIT extends AbstractModerationIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void actuatorPrometheus_exposesJvmAndHttpMetrics() {
        WebTestClient client =
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        // Warmup: trigger an HTTP request so http_server_requests_seconds_count gets recorded
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();

        String body =
                client.get()
                        .uri("/actuator/prometheus")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody();

        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("http_server_requests_seconds_count");
    }
}
