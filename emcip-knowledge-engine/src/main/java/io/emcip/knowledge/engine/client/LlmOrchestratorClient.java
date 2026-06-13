package io.emcip.knowledge.engine.client;

import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedRelationship;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    public ExtractionResult extract(String text, String conceptTypes, String relationshipTypes) {
        String prompt =
                String.format(
                        """
                        Extract entities and relationships from the following text.
                        Concept types: %s
                        Relationship types: %s

                        Return JSON with "entities" (array of {type, label}) and \
                        "relationships" (array of {type, source, target}).

                        Text: %s
                        """,
                        conceptTypes, relationshipTypes, text);

        Map<String, String> request = Map.of("prompt", prompt, "taskType", "EXTRACT");

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
