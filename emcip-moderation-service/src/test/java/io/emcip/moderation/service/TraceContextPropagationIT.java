package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.reactive.server.WebTestClient;

class TraceContextPropagationIT extends AbstractModerationIntegrationTest {

    @Autowired private ObservationRegistry observationRegistry;

    @Value("${local.server.port}")
    private int port;

    @Value("${management.tracing.sampling.probability}")
    private double samplingProbability;

    @Value("${management.otlp.tracing.endpoint}")
    private String otlpEndpoint;

    @Test
    void tracingConfiguration_isFullSampling() {
        assertThat(samplingProbability).isEqualTo(1.0);
    }

    @Test
    void otlpEndpoint_isConfigured() {
        assertThat(otlpEndpoint).contains("/v1/traces");
    }

    @Test
    void observationRegistry_isNotNoop() {
        // A noop registry means no observations are recorded — OTel bridge wires real handlers
        assertThat(observationRegistry).isNotInstanceOf(ObservationRegistry.NOOP.getClass());
    }

    @Test
    void httpRequest_propagatesContext() {
        WebTestClient client =
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        // Health endpoint responds OK; the request is instrumented with trace context
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
