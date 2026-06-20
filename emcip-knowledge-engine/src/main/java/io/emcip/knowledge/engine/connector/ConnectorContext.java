package io.emcip.knowledge.engine.connector;

import java.time.Instant;
import java.util.UUID;
import org.springframework.lang.Nullable;

public record ConnectorContext(@Nullable String apiKey, UUID tenantId, Instant since) {}
