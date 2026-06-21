package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.QueryStrategy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ResearchStrategyServiceTest {

    @Mock private LlmOrchestratorClient llmClient;

    private ResearchStrategyService service;

    @BeforeEach
    void setUp() {
        service = new ResearchStrategyService(llmClient, new ObjectMapper());
    }

    @Test
    void decompose_parsesLlmJsonResponse_intoSubQuestions() {
        String llmResponse =
                """
[
  {"subQuestion": "What topics does Alice discuss?", "strategy": "PERSON_ANALYSIS"},
  {"subQuestion": "What do we know about AI in the group?", "strategy": "TOPIC_EXPLORATION"}
]
""";
        when(llmClient.analyse(anyString(), anyString())).thenReturn(llmResponse);

        List<ResearchStrategyService.SubQuestion> result =
                service.decompose("Tell me about Alice's views on AI");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).subQuestion()).isEqualTo("What topics does Alice discuss?");
        assertThat(result.get(0).strategy()).isEqualTo(QueryStrategy.PERSON_ANALYSIS);
        assertThat(result.get(1).strategy()).isEqualTo(QueryStrategy.TOPIC_EXPLORATION);
    }

    @Test
    void decompose_returnsSingleFallback_whenLlmResponseUnparseable() {
        when(llmClient.analyse(anyString(), anyString())).thenReturn("not valid json");

        List<ResearchStrategyService.SubQuestion> result =
                service.decompose("What do we know about climate change?");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).subQuestion()).isEqualTo("What do we know about climate change?");
        assertThat(result.get(0).strategy()).isEqualTo(QueryStrategy.TOPIC_EXPLORATION);
    }

    @Test
    void decompose_returnsSingleFallback_whenLlmReturnsNull() {
        when(llmClient.analyse(anyString(), anyString())).thenReturn(null);

        List<ResearchStrategyService.SubQuestion> result =
                service.decompose("Who are the key people?");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).strategy()).isEqualTo(QueryStrategy.TOPIC_EXPLORATION);
    }
}
