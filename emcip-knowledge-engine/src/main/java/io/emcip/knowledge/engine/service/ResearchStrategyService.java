package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.QueryStrategy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchStrategyService {

    private final LlmOrchestratorClient llmClient;
    private final ObjectMapper objectMapper;

    public record SubQuestion(String subQuestion, QueryStrategy strategy) {}

    private static final String DECOMPOSE_PROMPT_TEMPLATE =
            """
            You are a research planning assistant. Decompose the following research question into \
            2-5 focused sub-questions. For each sub-question, choose the most appropriate strategy:
            - TOPIC_EXPLORATION: "What do we know about X?"
            - PERSON_ANALYSIS: "What does person X discuss or think?"
            - OPINION_MAPPING: "Who holds what position on X?"
            - COMPARISON: "How do groups or people differ on X?"
            - FACT_VERIFICATION: "Is claim X supported by evidence?"

            Respond ONLY with a JSON array. Example:
            [{"subQuestion": "...", "strategy": "TOPIC_EXPLORATION"}]

            Research question: %s
            """;

    public List<SubQuestion> decompose(String question) {
        String prompt = DECOMPOSE_PROMPT_TEMPLATE.formatted(question);
        String response = llmClient.analyse(prompt, "RESEARCH");

        if (response == null || response.isBlank()) {
            return fallback(question);
        }

        try {
            String json = response.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
            }
            return objectMapper.readValue(json, new TypeReference<List<SubQuestion>>() {});
        } catch (Exception e) {
            log.warn(
                    "Failed to parse LLM decomposition response, using fallback: {}",
                    e.getMessage());
            return fallback(question);
        }
    }

    private List<SubQuestion> fallback(String question) {
        return List.of(new SubQuestion(question, QueryStrategy.TOPIC_EXPLORATION));
    }
}
