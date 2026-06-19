package io.emcip.knowledge.engine.connector;

import java.util.List;

/**
 * Implemented by every enrichment connector. Spring-managed (@Component). Returns a plain List —
 * knowledge-engine is JPA/blocking; no reactive types here.
 */
public interface KnowledgeConnector {

    /** Unique identifier matching vendor_id in ke_vendor_api_keys / ke_enrichment_sources. */
    String vendorId();

    String displayName();

    boolean requiresApiKey();

    /**
     * Fetch results for the given request. Throws {@link ConnectorException} only for
     * connector-level failures (auth, network). Per-item errors must be caught inside the
     * implementation and excluded from the returned list.
     */
    List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx);
}
