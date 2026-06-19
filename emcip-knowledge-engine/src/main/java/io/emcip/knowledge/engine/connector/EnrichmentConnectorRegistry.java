package io.emcip.knowledge.engine.connector;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EnrichmentConnectorRegistry {

    private final Map<String, KnowledgeConnector> byVendorId;

    public EnrichmentConnectorRegistry(List<KnowledgeConnector> connectors) {
        this.byVendorId =
                connectors.stream()
                        .collect(
                                Collectors.toMap(
                                        KnowledgeConnector::vendorId, Function.identity()));
        log.info("Registered {} enrichment connectors: {}", byVendorId.size(), byVendorId.keySet());
    }

    public Optional<KnowledgeConnector> find(String vendorId) {
        return Optional.ofNullable(byVendorId.get(vendorId));
    }

    public List<KnowledgeConnector> all() {
        return List.copyOf(byVendorId.values());
    }
}
