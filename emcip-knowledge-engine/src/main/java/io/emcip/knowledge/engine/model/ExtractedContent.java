package io.emcip.knowledge.engine.model;

import java.util.Map;

public record ExtractedContent(String text, Map<String, String> metadata) {}
