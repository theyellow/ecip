package io.emcip.knowledge.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import io.emcip.knowledge.engine.model.ResearchRequest;
import io.emcip.knowledge.engine.model.ResearchSessionDto;
import io.emcip.knowledge.engine.repository.ResearchEvidenceRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import io.emcip.knowledge.engine.service.ResearchAgentService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ResearchControllerTest {

    @Mock private ResearchAgentService agentService;
    @Mock private ResearchSessionRepository sessionRepository;
    @Mock private ResearchEvidenceRepository evidenceRepository;

    private ResearchController controller;

    @BeforeEach
    void setUp() {
        controller = new ResearchController(agentService, sessionRepository, evidenceRepository);
    }

    private ResearchSession buildSession(UUID id, UUID tenantId, ResearchStatus status) {
        ResearchSession s = new ResearchSession();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setQuestion("Test question");
        s.setStatus(status);
        s.setMaxIterations(10);
        s.setMaxLlmCalls(20);
        s.setCostLimitUsd(1.00);
        return s;
    }

    @Test
    void startResearch_returns201_withSessionDto() {
        UUID tenantId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ResearchRequest request = new ResearchRequest("Test question", tenantId, 10, 20, 1.00);

        ResearchSession session = buildSession(sessionId, tenantId, ResearchStatus.COMPLETED);
        when(agentService.startResearch(any())).thenReturn(session);
        when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(sessionId))
                .thenReturn(List.of());

        ResponseEntity<ResearchSessionDto> response = controller.startResearch(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(sessionId);
        assertThat(response.getBody().status()).isEqualTo(ResearchStatus.COMPLETED);
    }

    @Test
    void getSession_returns404_whenNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        ResponseEntity<ResearchSessionDto> response = controller.getSession(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listSessions_returnsSessions_forTenant() {
        UUID tenantId = UUID.randomUUID();
        ResearchSession session =
                buildSession(UUID.randomUUID(), tenantId, ResearchStatus.COMPLETED);
        when(sessionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(session));
        when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(any()))
                .thenReturn(List.of());

        ResponseEntity<List<ResearchSessionDto>> response = controller.listSessions(tenantId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void pauseSession_returns404_whenNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(agentService.pauseSession(sessionId)).thenReturn(Optional.empty());

        ResponseEntity<ResearchSessionDto> response = controller.pauseSession(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resumeSession_returns404_whenNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(agentService.resumeSession(sessionId)).thenReturn(Optional.empty());

        ResponseEntity<ResearchSessionDto> response = controller.resumeSession(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
