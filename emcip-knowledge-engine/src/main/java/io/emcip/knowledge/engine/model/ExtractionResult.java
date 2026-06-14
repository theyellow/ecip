package io.emcip.knowledge.engine.model;

import java.util.List;
import java.util.Map;

public record ExtractionResult(
        List<ExtractedEntity> entities, List<ExtractedRelationship> relationships) {

    public record ExtractedEntity(String type, String label, Map<String, Object> properties) {}

    public record ExtractedRelationship(
            String type, String source, String target, Map<String, Object> properties) {}
}
