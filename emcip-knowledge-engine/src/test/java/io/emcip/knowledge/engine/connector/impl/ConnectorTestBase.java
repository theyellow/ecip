package io.emcip.knowledge.engine.connector.impl;

import java.io.IOException;
import java.time.Instant;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.client.RestClient;

abstract class ConnectorTestBase {

    protected MockWebServer server;
    protected RestClient restClient;

    /** Override to point at the mock server's base URL. */
    protected String baseUrl() {
        return server.url("/").toString();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        restClient =
                RestClient.builder()
                        .baseUrl(baseUrl())
                        .defaultHeader("User-Agent", "EMCIP-Test/1.0")
                        .build();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    protected static Instant epoch() {
        return Instant.parse("2026-01-01T00:00:00Z");
    }
}
