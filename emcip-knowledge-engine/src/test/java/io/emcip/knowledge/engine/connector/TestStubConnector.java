package io.emcip.knowledge.engine.connector;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;

/**
 * Stub connector for integration tests. Returns one deterministic result per call. Registered only
 * in the test Spring context.
 */
@TestComponent
public class TestStubConnector implements KnowledgeConnector {

    public static final String VENDOR_ID = "stub";

    @Override
    public String vendorId() {
        return VENDOR_ID;
    }

    @Override
    public String displayName() {
        return "Stub Connector";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        return List.of(
                new EnrichmentResult(
                        "stub-ext-001",
                        "Stub paper about " + request.query(),
                        "Abstract of stub paper.",
                        "https://stub.example/001",
                        VENDOR_ID,
                        Instant.parse("2026-01-15T00:00:00Z"),
                        Map.of("authors", List.of("Alice", "Bob"))));
    }
}
