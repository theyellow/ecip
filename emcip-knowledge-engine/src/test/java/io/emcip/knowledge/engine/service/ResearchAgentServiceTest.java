package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.entity.QueryStrategy;
import io.emcip.knowledge.engine.entity.ReportTemplate;
import io.emcip.knowledge.engine.entity.ResearchEvidence;
import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import io.emcip.knowledge.engine.model.ResearchRequest;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.repository.ResearchEvidenceRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchAgentServiceTest {

    @Mock private ResearchSessionRepository sessionRepository;
    @Mock private ResearchEvidenceRepository evidenceRepository;
    @Mock private ResearchStrategyService strategyService;
    @Mock private KnowledgeQueryService queryService;
    @Mock private KnowledgeEventPublisher eventPublisher;
    @Mock private WebSearchService webSearchService;
    @Mock private ResearchReportService reportService;

    private ResearchAgentService service;

    @BeforeEach
    void setUp() {
        service =
                new ResearchAgentService(
                        sessionRepository,
                        evidenceRepository,
                        strategyService,
                        queryService,
                        eventPublisher,
                        webSearchService,
                        reportService);
    }

    @Test
    void startResearch_createsSession_andRunsLoop() {
        UUID tenantId = UUID.randomUUID();
        ResearchRequest request =
                new ResearchRequest(
                        "Tell me about Alice's views on AI", tenantId, 10, 20, 1.00, false, null);

        ResearchStrategyService.SubQuestion subQ =
                new ResearchStrategyService.SubQuestion(
                        "What topics does Alice discuss?", QueryStrategy.PERSON_ANALYSIS);
        when(strategyService.decompose(anyString())).thenReturn(List.of(subQ));

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setContent("Alice frequently discusses AI ethics.");
        doc.setSourceType("TELEGRAM_MESSAGE");
        doc.setSourceRef("msg-123");

        SearchResponse response =
                new SearchResponse(
                        List.of(), List.of(new SearchResponse.DocumentResult(doc, 0.88)));
        when(queryService.search(any())).thenReturn(response);

        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(any()))
                .thenReturn(List.of());

        ResearchSession result = service.startResearch(request);

        assertThat(result.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
        assertThat(result.getIterationsUsed()).isEqualTo(1);

        ArgumentCaptor<ResearchEvidence> evidenceCaptor =
                ArgumentCaptor.forClass(ResearchEvidence.class);
        verify(evidenceRepository, atLeastOnce()).save(evidenceCaptor.capture());
        ResearchEvidence saved = evidenceCaptor.getValue();
        assertThat(saved.getFinding()).contains("Alice frequently discusses AI ethics.");
        assertThat(saved.getSourceRef()).isEqualTo("msg-123");
        assertThat(saved.getConfidenceScore()).isEqualTo(0.88);
        assertThat(saved.getQueryStrategy()).isEqualTo(QueryStrategy.PERSON_ANALYSIS);
        verify(eventPublisher)
                .publishResearchCompleted(
                        nullable(UUID.class), eq(ResearchStatus.COMPLETED), nullable(UUID.class));
    }

    @Test
    void startResearch_stopsWhenMaxIterationsReached() {
        UUID tenantId = UUID.randomUUID();
        ResearchRequest request =
                new ResearchRequest("A complex question", tenantId, 1, 20, 1.00, false, null);

        List<ResearchStrategyService.SubQuestion> subQs =
                List.of(
                        new ResearchStrategyService.SubQuestion(
                                "Q1", QueryStrategy.TOPIC_EXPLORATION),
                        new ResearchStrategyService.SubQuestion(
                                "Q2", QueryStrategy.TOPIC_EXPLORATION),
                        new ResearchStrategyService.SubQuestion(
                                "Q3", QueryStrategy.TOPIC_EXPLORATION));
        when(strategyService.decompose(anyString())).thenReturn(subQs);
        when(queryService.search(any())).thenReturn(new SearchResponse(List.of(), List.of()));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(any()))
                .thenReturn(List.of());

        ResearchSession result = service.startResearch(request);

        // Only 1 iteration processed despite 3 sub-questions (maxIterations=1)
        assertThat(result.getIterationsUsed()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
    }

    @Test
    void startResearch_setsFailedStatus_whenStrategyServiceThrows() {
        UUID tenantId = UUID.randomUUID();
        ResearchRequest request = new ResearchRequest("Q", tenantId, 10, 20, 1.00, false, null);

        when(strategyService.decompose(anyString()))
                .thenThrow(new RuntimeException("LLM unavailable"));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchSession result = service.startResearch(request);

        assertThat(result.getStatus()).isEqualTo(ResearchStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("LLM unavailable");
        verify(eventPublisher)
                .publishResearchCompleted(
                        nullable(UUID.class), eq(ResearchStatus.FAILED), nullable(UUID.class));
    }

    @Test
    void startResearch_collectsWebEvidence_whenWebSearchEnabled() {
        UUID tenantId = UUID.randomUUID();
        ResearchRequest request =
                new ResearchRequest(
                        "AI ethics in social media", tenantId, 10, 20, 1.00, true, null);

        ResearchStrategyService.SubQuestion subQ =
                new ResearchStrategyService.SubQuestion(
                        "What are AI ethics concerns?", QueryStrategy.TOPIC_EXPLORATION);
        when(strategyService.decompose(anyString())).thenReturn(List.of(subQ));
        when(queryService.search(any())).thenReturn(new SearchResponse(List.of(), List.of()));

        io.emcip.knowledge.engine.connector.EnrichmentResult webResult =
                new io.emcip.knowledge.engine.connector.EnrichmentResult(
                        "https://example.com/ai",
                        "AI Ethics Overview",
                        "A discussion of AI ethics principles",
                        "https://example.com/ai",
                        "searxng",
                        null,
                        java.util.Map.of());
        when(webSearchService.search(anyString(), any(UUID.class))).thenReturn(List.of(webResult));

        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(any()))
                .thenReturn(List.of());

        ResearchSession result = service.startResearch(request);

        assertThat(result.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
        ArgumentCaptor<ResearchEvidence> captor = ArgumentCaptor.forClass(ResearchEvidence.class);
        verify(evidenceRepository, atLeastOnce()).save(captor.capture());

        boolean hasWebEvidence =
                captor.getAllValues().stream()
                        .anyMatch(e -> "WEB_SEARCH".equals(e.getSourceType()));
        assertThat(hasWebEvidence).isTrue();
    }

    @Test
    void startResearch_triggersReportGeneration_whenCompleted() {
        UUID tenantId = UUID.randomUUID();
        ResearchRequest request =
                new ResearchRequest("Research question", tenantId, 10, 20, 1.00, false, null);

        when(strategyService.decompose(anyString()))
                .thenReturn(
                        List.of(
                                new ResearchStrategyService.SubQuestion(
                                        "Q1", QueryStrategy.TOPIC_EXPLORATION)));
        when(queryService.search(any())).thenReturn(new SearchResponse(List.of(), List.of()));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(any()))
                .thenReturn(List.of());

        service.startResearch(request);

        verify(reportService).generateReport(any(), any(), eq(ReportTemplate.TOPIC));
    }
}
