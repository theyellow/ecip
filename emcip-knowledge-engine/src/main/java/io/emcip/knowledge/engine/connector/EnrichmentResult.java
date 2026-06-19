package io.emcip.knowledge.engine.connector;

import java.time.Instant;
import java.util.Map;
import org.springframework.lang.Nullable;

public record EnrichmentResult(
        String externalId,
        String title,
        @Nullable String content,
        String url,
        String sourceVendorId,
        Instant publishedAt,
        Map<String, Object> metadata) {}
