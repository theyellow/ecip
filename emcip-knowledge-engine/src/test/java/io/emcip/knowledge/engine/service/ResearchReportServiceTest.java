package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.QueryStrategy;
import io.emcip.knowledge.engine.entity.ReportTemplate;
import io.emcip.knowledge.engine.entity.ResearchEvidence;
import io.emcip.knowledge.engine.entity.ResearchReport;
import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import io.emcip.knowledge.engine.repository.ResearchReportRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchReportServiceTest {

    @Mock private LlmOrchestratorClient llmClient;
    @Mock private ResearchReportRepository reportRepository;

    private ResearchReportService service;

    @BeforeEach
    void setUp() {
        service = new ResearchReportService(llmClient, reportRepository);
    }

    private ResearchSession buildSession(String question) {
        ResearchSession s = new ResearchSession();
        s.setTenantId(UUID.randomUUID());
        s.setQuestion(question);
        s.setStatus(ResearchStatus.COMPLETED);
        return s;
    }

    private ResearchEvidence buildEvidence(
            ResearchSession session, String subQ, String finding, String sourceRef) {
        ResearchEvidence e = new ResearchEvidence();
        e.setSession(session);
        e.setSubQuestion(subQ);
        e.setQueryStrategy(QueryStrategy.TOPIC_EXPLORATION);
        e.setFinding(finding);
        e.setSourceType("CHAT_MESSAGE");
        e.setSourceRef(sourceRef);
        e.setConfidenceScore(0.85);
        e.setIteration(0);
        return e;
    }

    @Test
    void generateReport_callsLlmWithEvidencePrompt_andStoresReport() {
        ResearchSession session = buildSession("What are the risks of AI in moderation?");
        ResearchEvidence evidence =
                buildEvidence(
                        session,
                        "What concerns do users raise?",
                        "Users worry about bias in automated decisions",
                        "msg-001");

        String llmResponse =
                "## Executive Summary\n"
                        + "AI moderation poses several risks.\n\n"
                        + "## Key Findings\n"
                        + "- Bias risk\n";
        when(llmClient.analyse(anyString(), eq("REPORT"))).thenReturn(llmResponse);
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchReport report =
                service.generateReport(session, List.of(evidence), ReportTemplate.TOPIC);

        assertThat(report).isNotNull();
        assertThat(report.getContent()).isEqualTo(llmResponse);
        assertThat(report.getTemplate()).isEqualTo(ReportTemplate.TOPIC);
        assertThat(report.getTitle()).contains("AI in moderation");
        assertThat(report.getTenantId()).isEqualTo(session.getTenantId());
        assertThat(report.getVersion()).isEqualTo(1);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).analyse(promptCaptor.capture(), eq("REPORT"));
        assertThat(promptCaptor.getValue()).contains("What are the risks of AI in moderation?");
        assertThat(promptCaptor.getValue()).contains("Users worry about bias");
    }

    @Test
    void generateReport_usesPersonTemplate_whenReportTemplateIsPerson() {
        ResearchSession session = buildSession("Who is Alice Smith?");
        when(llmClient.analyse(anyString(), eq("REPORT")))
                .thenReturn("## Executive Summary\nAlice is...");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchReport report = service.generateReport(session, List.of(), ReportTemplate.PERSON);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).analyse(promptCaptor.capture(), eq("REPORT"));
        assertThat(promptCaptor.getValue()).contains("profiling an individual");
    }

    @Test
    void generateReport_usesFactCheckTemplate_whenReportTemplateIsFactCheck() {
        ResearchSession session = buildSession("Is claim X true?");
        when(llmClient.analyse(anyString(), eq("REPORT")))
                .thenReturn("## Executive Summary\nSupported.");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generateReport(session, List.of(), ReportTemplate.FACT_CHECK);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).analyse(promptCaptor.capture(), eq("REPORT"));
        assertThat(promptCaptor.getValue()).contains("fact-checking researcher");
    }

    @Test
    void generateReport_usesTemplateFromOrchestrator_whenTemplateFound() {
        ResearchSession session = buildSession("Climate change impacts on agriculture");
        ResearchEvidence evidence =
                buildEvidence(
                        session,
                        "What crops are most affected?",
                        "Wheat yields declining in southern Europe",
                        "msg-042");

        LlmOrchestratorClient.TemplateResponse templateResponse =
                new LlmOrchestratorClient.TemplateResponse(
                        "research_topic",
                        "You are an expert research synthesizer.",
                        "Topic: {{topic}}\n\nEvidence:\n{{evidence}}",
                        2000,
                        0.3);

        when(llmClient.getTemplate("research_topic")).thenReturn(templateResponse);
        when(llmClient.analyse(anyString(), eq("REPORT"))).thenReturn("## Report\nSome findings.");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchReport report =
                service.generateReport(session, List.of(evidence), ReportTemplate.TOPIC);

        assertThat(report).isNotNull();
        assertThat(report.getContent()).isEqualTo("## Report\nSome findings.");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).analyse(promptCaptor.capture(), eq("REPORT"));
        String capturedPrompt = promptCaptor.getValue();
        assertThat(capturedPrompt).contains("Climate change impacts on agriculture");
        assertThat(capturedPrompt).contains("Wheat yields declining in southern Europe");
        assertThat(capturedPrompt).doesNotContain("{{topic}}");
        assertThat(capturedPrompt).doesNotContain("{{evidence}}");
        // Verify it uses the template structure, not the hardcoded prompt
        assertThat(capturedPrompt).contains("Topic: Climate change impacts on agriculture");
    }

    @Test
    void generateReport_storesFallbackContent_whenLlmReturnsNull() {
        ResearchSession session = buildSession("Is claim X true?");
        when(llmClient.analyse(anyString(), eq("REPORT"))).thenReturn(null);
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchReport report =
                service.generateReport(session, List.of(), ReportTemplate.FACT_CHECK);

        assertThat(report.getContent()).contains("Report Generation Failed");
    }
}
