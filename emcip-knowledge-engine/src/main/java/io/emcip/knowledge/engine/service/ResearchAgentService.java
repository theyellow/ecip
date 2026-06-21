package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.ResearchEvidence;
import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import io.emcip.knowledge.engine.model.ResearchRequest;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.repository.ResearchEvidenceRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives the deep research execution loop.
 *
 * <p><b>Synchronous execution note:</b> {@link #startResearch} runs the full loop inline on the
 * calling thread, within a single transaction. This means {@link #pauseSession} cannot interrupt a
 * session that is currently executing — the {@code RUNNING} status is not visible to other threads
 * until the transaction commits, by which time the session is already {@code COMPLETED} or {@code
 * FAILED}.
 *
 * <p>TODO (Epic 27B or later): Dispatch {@link #runLoop} asynchronously (e.g., via {@code @Async} +
 * a thread pool or a dedicated Kafka-driven workflow) to make mid-loop pause and true long-running
 * session management possible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchAgentService {

    /** Estimated USD cost charged per research iteration (decomposition + one knowledge query). */
    private static final double COST_PER_ITERATION_USD = 0.01;

    private final ResearchSessionRepository sessionRepository;
    private final ResearchEvidenceRepository evidenceRepository;
    private final ResearchStrategyService strategyService;
    private final KnowledgeQueryService queryService;
    private final KnowledgeEventPublisher eventPublisher;

    @Transactional
    public ResearchSession startResearch(ResearchRequest request) {
        ResearchSession session = new ResearchSession();
        session.setTenantId(request.tenantId());
        session.setQuestion(request.question());
        session.setMaxIterations(request.maxIterations());
        session.setMaxLlmCalls(request.maxLlmCalls());
        session.setCostLimitUsd(request.costLimitUsd());
        session.setStatus(ResearchStatus.CREATED);
        sessionRepository.save(session);

        session.setStatus(ResearchStatus.RUNNING);
        sessionRepository.save(session);

        try {
            runLoop(session);
            session.setStatus(ResearchStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Research session {} failed: {}", session.getId(), e.getMessage(), e);
            session.setStatus(ResearchStatus.FAILED);
            session.setErrorMessage(e.getMessage());
        }

        sessionRepository.save(session);
        publishCompletionEvent(session);
        return session;
    }

    @Transactional
    public Optional<ResearchSession> pauseSession(UUID sessionId) {
        return sessionRepository
                .findById(sessionId)
                .map(
                        session -> {
                            if (session.getStatus() == ResearchStatus.RUNNING) {
                                session.setStatus(ResearchStatus.PAUSED);
                                sessionRepository.save(session);
                            } else {
                                log.debug(
                                        "Session {} is not in RUNNING state (current: {}), skipping"
                                                + " pause",
                                        session.getId(),
                                        session.getStatus());
                            }
                            return session;
                        });
    }

    @Transactional
    public Optional<ResearchSession> resumeSession(UUID sessionId) {
        return sessionRepository
                .findById(sessionId)
                .map(
                        session -> {
                            if (session.getStatus() == ResearchStatus.PAUSED) {
                                session.setStatus(ResearchStatus.RUNNING);
                                sessionRepository.save(session);
                                try {
                                    runLoop(session);
                                    session.setStatus(ResearchStatus.COMPLETED);
                                } catch (Exception e) {
                                    log.error(
                                            "Research session {} failed on resume: {}",
                                            session.getId(),
                                            e.getMessage(),
                                            e);
                                    session.setStatus(ResearchStatus.FAILED);
                                    session.setErrorMessage(e.getMessage());
                                }
                                sessionRepository.save(session);
                            } else {
                                log.debug(
                                        "Session {} is not in PAUSED state (current: {}), skipping"
                                                + " resume",
                                        session.getId(),
                                        session.getStatus());
                            }
                            return session;
                        });
    }

    private void runLoop(ResearchSession session) {
        List<ResearchStrategyService.SubQuestion> subQuestions =
                strategyService.decompose(session.getQuestion());
        session.incrementLlmCalls(1);

        int iteration = 0;
        for (ResearchStrategyService.SubQuestion subQ : subQuestions) {
            if (!session.isWithinLimits()) {
                log.info(
                        "Session {} reached limits after {} iterations",
                        session.getId(),
                        session.getIterationsUsed());
                break;
            }

            SearchRequest searchRequest =
                    new SearchRequest(
                            subQ.subQuestion(),
                            SearchRequest.SearchType.HYBRID,
                            session.getTenantId(),
                            null,
                            null,
                            10);

            SearchResponse response = queryService.search(searchRequest);

            collectEvidence(session, subQ, response, iteration);
            session.incrementIterations(1);
            session.setCostUsedUsd(session.getCostUsedUsd() + COST_PER_ITERATION_USD);
            iteration++;

            sessionRepository.save(session);
        }
    }

    private void collectEvidence(
            ResearchSession session,
            ResearchStrategyService.SubQuestion subQ,
            SearchResponse response,
            int iteration) {

        for (SearchResponse.DocumentResult dr : response.documentResults()) {
            ResearchEvidence evidence = new ResearchEvidence();
            evidence.setSession(session);
            evidence.setSubQuestion(subQ.subQuestion());
            evidence.setQueryStrategy(subQ.strategy());
            evidence.setFinding(dr.document().getContent());
            evidence.setSourceType(dr.document().getSourceType());
            evidence.setSourceRef(dr.document().getSourceRef());
            evidence.setConfidenceScore(dr.similarity());
            evidence.setIteration(iteration);
            evidenceRepository.save(evidence);
        }

        for (SearchResponse.GraphNodeResult gr : response.graphResults()) {
            ResearchEvidence evidence = new ResearchEvidence();
            evidence.setSession(session);
            evidence.setSubQuestion(subQ.subQuestion());
            evidence.setQueryStrategy(subQ.strategy());
            evidence.setFinding(gr.node().label() + " [" + gr.node().conceptType() + "]");
            evidence.setSourceType("GRAPH_NODE");
            evidence.setSourceRef(gr.node().id().toString());
            evidence.setConfidenceScore(gr.score());
            evidence.setIteration(iteration);
            evidenceRepository.save(evidence);
        }
    }

    private void publishCompletionEvent(ResearchSession session) {
        try {
            eventPublisher.publishResearchCompleted(
                    session.getId(), session.getStatus(), session.getTenantId());
        } catch (Exception e) {
            log.warn(
                    "Failed to publish research completion event for session {}: {}",
                    session.getId(),
                    e.getMessage());
        }
    }
}
