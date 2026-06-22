package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.ReportTemplate;
import io.emcip.knowledge.engine.entity.ResearchEvidence;
import io.emcip.knowledge.engine.entity.ResearchReport;
import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.repository.ResearchReportRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchReportService {

    private final LlmOrchestratorClient llmClient;
    private final ResearchReportRepository reportRepository;

    private static final String TOPIC_PROMPT_TEMPLATE =
            """
            You are a research analyst synthesizing community intelligence.
            Based on the evidence below about "%s", write a structured research report.

            Format your response as Markdown with exactly these sections:
            ## Executive Summary
            (2–3 sentences summarizing key findings)

            ## Key Findings
            (3–5 bullet points of the most important discoveries)

            ## Community Perspective
            (What community members discuss, believe, or are concerned about)

            ## Factual Context
            (Verified facts from external sources that provide context)

            ## Contradictions & Open Questions
            (Areas of disagreement, unverified claims, or open questions)

            ## Sources
            (List each source as: - [source_type] source_ref)

            Evidence collected:
            %s
            """;

    private static final String PERSON_PROMPT_TEMPLATE =
            """
            You are a research analyst profiling an individual based on community intelligence.
            Based on the evidence below about "%s", write a structured person analysis report.

            Format your response as Markdown with exactly these sections:
            ## Executive Summary
            (2–3 sentences about this person's role and significance)

            ## Key Findings
            (3–5 bullet points about this person's notable activities or statements)

            ## Community Perspective
            (How community members perceive and discuss this person)

            ## Factual Context
            (Verified facts about this person from external sources)

            ## Contradictions & Open Questions
            (Inconsistencies in reporting or open questions)

            ## Sources
            (List each source as: - [source_type] source_ref)

            Evidence collected:
            %s
            """;

    private static final String FACT_CHECK_PROMPT_TEMPLATE =
            """
            You are a fact-checking researcher.
            Based on the evidence below about "%s", write a structured fact-check report.

            Format your response as Markdown with exactly these sections:
            ## Executive Summary
            (Verdict: Supported / Unsupported / Partially Supported / Insufficient Evidence)

            ## Key Findings
            (3–5 bullet points of evidence for or against the claim)

            ## Community Perspective
            (What community members say about this claim)

            ## Factual Context
            (Verified facts from external sources)

            ## Contradictions & Open Questions
            (Conflicting evidence or remaining uncertainty)

            ## Sources
            (List each source as: - [source_type] source_ref)

            Evidence collected:
            %s
            """;

    /**
     * Synthesises all collected evidence into a Markdown research report using the LLM. Stores and
     * returns the report. Non-fatal: if LLM fails, stores a placeholder report.
     */
    @Transactional
    public ResearchReport generateReport(
            ResearchSession session, List<ResearchEvidence> evidence, ReportTemplate template) {

        String promptTemplate = selectPromptTemplate(template);
        String evidenceSummary = buildEvidenceSummary(evidence);
        String prompt = promptTemplate.formatted(session.getQuestion(), evidenceSummary);

        String content = llmClient.analyse(prompt, "REPORT");
        if (content == null || content.isBlank()) {
            log.warn("LLM returned no content for report on session {}", session.getId());
            content =
                    "# Report Generation Failed\n\n"
                            + "The LLM could not generate a report for this session.";
        }

        ResearchReport report = new ResearchReport();
        report.setTenantId(session.getTenantId());
        report.setSession(session);
        report.setTemplate(template);
        report.setTitle(buildTitle(session.getQuestion(), template));
        report.setContent(content);

        return reportRepository.save(report);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private String selectPromptTemplate(ReportTemplate template) {
        return switch (template) {
            case TOPIC -> TOPIC_PROMPT_TEMPLATE;
            case PERSON -> PERSON_PROMPT_TEMPLATE;
            case FACT_CHECK -> FACT_CHECK_PROMPT_TEMPLATE;
        };
    }

    private String buildEvidenceSummary(List<ResearchEvidence> evidence) {
        if (evidence.isEmpty()) return "(No evidence collected)";

        Map<String, List<ResearchEvidence>> bySubQuestion =
                evidence.stream().collect(Collectors.groupingBy(ResearchEvidence::getSubQuestion));

        StringBuilder sb = new StringBuilder();
        bySubQuestion.forEach(
                (subQ, items) -> {
                    sb.append("### ").append(subQ).append("\n");
                    for (ResearchEvidence e : items) {
                        sb.append("- [")
                                .append(e.getSourceType())
                                .append("] ")
                                .append(e.getFinding())
                                .append(" (confidence: ")
                                .append(String.format("%.2f", e.getConfidenceScore()))
                                .append(")\n");
                        sb.append("  Source: ").append(e.getSourceRef()).append("\n");
                    }
                    sb.append("\n");
                });
        return sb.toString();
    }

    private String buildTitle(String question, ReportTemplate template) {
        String prefix =
                switch (template) {
                    case TOPIC -> "Research Report: ";
                    case PERSON -> "Person Analysis: ";
                    case FACT_CHECK -> "Fact Check: ";
                };
        String truncated = question.length() <= 80 ? question : question.substring(0, 77) + "...";
        return prefix + truncated;
    }
}
