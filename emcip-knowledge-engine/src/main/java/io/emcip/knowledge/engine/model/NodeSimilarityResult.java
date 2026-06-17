package io.emcip.knowledge.engine.model;

import java.util.UUID;

public record NodeSimilarityResult(UUID nodeId, String label, double score) {}
