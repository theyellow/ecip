package io.emcip.knowledge.engine.client;

import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedRelationship;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
public class LlmOrchestratorClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public float[] embed(String text) {
        Map<String, String> request = Map.of("prompt", text, "taskType", "EMBED");

        var response =
                restClient
                        .post()
                        .uri("/api/analyse")
                        .body(request)
                        .retrieve()
                        .body(AnalyseResponse.class);

        if (response == null || !response.success()) {
            log.error("Embedding failed: {}", response);
            return new float[0];
        }

        return parseEmbedding(response.analysis());
    }

    public ExtractionResult extract(
            String text, List<ConceptType> conceptTypes, List<RelationshipType> relationshipTypes) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Extract structured knowledge from the text below.\n\n");

        prompt.append("CONCEPT TYPES:\n");
        for (ConceptType ct : conceptTypes) {
            prompt.append("- ")
                    .append(ct.getName())
                    .append(": ")
                    .append(ct.getDescription() != null ? ct.getDescription() : "")
                    .append("\n");
            if (ct.getProperties() != null && !ct.getProperties().isEmpty()) {
                String propNames =
                        ct.getProperties().stream()
                                .map(p -> (String) p.get("key"))
                                .filter(k -> k != null)
                                .collect(Collectors.joining(", "));
                prompt.append("  Properties: ")
                        .append(propNames.isEmpty() ? "none" : propNames)
                        .append("\n");
            }
        }

        prompt.append("\nRELATIONSHIP TYPES:\n");
        for (RelationshipType rt : relationshipTypes) {
            prompt.append("- ")
                    .append(rt.getName())
                    .append(": ")
                    .append(rt.getDescription() != null ? rt.getDescription() : "")
                    .append("\n");
            String src =
                    rt.getSourceTypes() != null ? String.join(", ", rt.getSourceTypes()) : "any";
            String tgt =
                    rt.getTargetTypes() != null ? String.join(", ", rt.getTargetTypes()) : "any";
            prompt.append("  Direction: ").append(src).append(" \u2192 ").append(tgt).append("\n");
        }

        prompt.append("\nTEXT:\n")
                .append(text)
                .append("\n\nReturn JSON:\n")
                .append(
                        "{\n"
                                + "  \"entities\": [{\"type\": \"<ConceptType name>\","
                                + " \"label\": \"<text>\", \"properties\": {}}],\n"
                                + "  \"relationships\": [{\"type\": \"<RelationshipType name>\","
                                + " \"source\": \"<label>\", \"target\": \"<label>\","
                                + " \"properties\": {}}]\n"
                                + "}");

        Map<String, String> request = Map.of("prompt", prompt.toString(), "taskType", "EXTRACT");

        var response =
                restClient
                        .post()
                        .uri("/api/analyse")
                        .body(request)
                        .retrieve()
                        .body(AnalyseResponse.class);

        if (response == null || !response.success()) {
            log.error("Extraction failed: {}", response);
            return new ExtractionResult(List.of(), List.of());
        }

        return parseExtractionResult(response.analysis());
    }

    public String analyse(String prompt, String taskType) {
        Map<String, String> request = Map.of("prompt", prompt, "taskType", taskType);

        var response =
                restClient
                        .post()
                        .uri("/api/analyse")
                        .body(request)
                        .retrieve()
                        .body(AnalyseResponse.class);

        if (response == null || !response.success()) {
            log.error("Analyse failed (taskType={}): {}", taskType, response);
            return null;
        }

        return response.analysis();
    }

    public String resolve(String label, String conceptType, List<String> candidates) {
        String prompt =
                String.format(
                        """
Entity resolution: does "%s" (type: %s) match any of these existing entities?
Candidates: %s
Respond with the matching candidate label, or "NEW" if no match.
""",
                        label, conceptType, String.join(", ", candidates));

        Map<String, String> request = Map.of("prompt", prompt, "taskType", "RESOLVE");

        var response =
                restClient
                        .post()
                        .uri("/api/analyse")
                        .body(request)
                        .retrieve()
                        .body(AnalyseResponse.class);

        if (response == null || !response.success()) {
            return "NEW";
        }

        return response.analysis().trim();
    }

    @SuppressWarnings("unchecked")
    private ExtractionResult parseExtractionResult(String json) {
        try {
            String cleaned = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            Map<String, Object> parsed = objectMapper.readValue(cleaned, Map.class);

            List<ExtractedEntity> entities = new ArrayList<>();
            if (parsed.containsKey("entities")) {
                for (Map<String, Object> e : (List<Map<String, Object>>) parsed.get("entities")) {
                    entities.add(
                            new ExtractedEntity(
                                    (String) e.get("type"),
                                    (String) e.get("label"),
                                    e.getOrDefault("properties", Collections.emptyMap())
                                                    instanceof Map<?, ?> p
                                            ? (Map<String, Object>) p
                                            : Map.of()));
                }
            }

            List<ExtractedRelationship> relationships = new ArrayList<>();
            if (parsed.containsKey("relationships")) {
                for (Map<String, Object> r :
                        (List<Map<String, Object>>) parsed.get("relationships")) {
                    relationships.add(
                            new ExtractedRelationship(
                                    (String) r.get("type"),
                                    (String) r.get("source"),
                                    (String) r.get("target"),
                                    r.getOrDefault("properties", Collections.emptyMap())
                                                    instanceof Map<?, ?> p
                                            ? (Map<String, Object>) p
                                            : Map.of()));
                }
            }

            return new ExtractionResult(entities, relationships);
        } catch (Exception e) {
            log.error("Failed to parse extraction result: {}", json, e);
            return new ExtractionResult(List.of(), List.of());
        }
    }

    private float[] parseEmbedding(String text) {
        try {
            String cleaned = text.replaceAll("[\\[\\]\\s]", "");
            String[] parts = cleaned.split(",");
            float[] embedding = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                embedding[i] = Float.parseFloat(parts[i].trim());
            }
            return embedding;
        } catch (Exception e) {
            log.error("Failed to parse embedding: {}", text, e);
            return new float[0];
        }
    }

    private record AnalyseResponse(boolean success, String analysis, String model) {}
}
