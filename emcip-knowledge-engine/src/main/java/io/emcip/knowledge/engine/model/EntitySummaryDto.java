package io.emcip.knowledge.engine.model;

import java.util.UUID;

public record EntitySummaryDto(String label, String conceptType, UUID nodeId) {}
