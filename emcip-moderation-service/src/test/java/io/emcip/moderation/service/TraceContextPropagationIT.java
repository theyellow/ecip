package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
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
        // A cold Testcontainers stack (Kafka + database health indicators) can legitimately take
        // longer than WebTestClient's 5s default response timeout; 30s gives it a realistic budget.
        WebTestClient client =
                WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port)
                        .responseTimeout(Duration.ofSeconds(30))
                        .build();

        // NOTE: this asserts only that the instrumented endpoint responds. It does NOT verify
        // trace propagation - despite this class's name. Tracing is currently not wired in any
        // EMCIP service: the modules declare micrometer-tracing-bridge-otel and the OTel SDK and
        // set management.tracing.* / management.otlp.*, but none pull in Spring Boot 4's tracing
        // autoconfiguration (spring-boot-starter-opentelemetry), which Boot 4 split out of
        // spring-boot-starter-actuator. As a result no Tracer bean is created and no tracing
        // ObservationHandler is registered — confirmed by inspecting the live ObservationRegistry
        // in this class, which only ever contains a metrics handler. Tracked separately;
        // strengthen this assertion (e.g. capture a server-side span/observation and assert its
        // trace ID matches a sent traceparent header) once tracing is actually wired.
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
