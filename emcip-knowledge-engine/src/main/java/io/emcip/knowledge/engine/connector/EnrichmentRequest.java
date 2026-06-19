package io.emcip.knowledge.engine.connector;

import java.util.Map;
import org.springframework.lang.Nullable;

public record EnrichmentRequest(
        TriggerMode mode,
        @Nullable String query,
        @Nullable String externalId,
        Map<String, String> params) {}
