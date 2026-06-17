package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ResolutionReviewServiceTest {

    @Mock private ResolutionFlagRepository flagRepository;
    @Mock private GraphRepository graphRepository;

    private ResolutionReviewService service;

    @BeforeEach
    void setUp() {
        service = new ResolutionReviewService(flagRepository, graphRepository);
    }

    private ResolutionFlag pendingFlag(UUID id, UUID candidateId, UUID similarId) {
        ResolutionFlag f = new ResolutionFlag();
        f.setId(id);
        f.setCandidateNodeId(candidateId);
        f.setSimilarNodeId(similarId);
        f.setCandidateLabel("AI");
        f.setSimilarLabel("artificial intelligence");
        f.setConceptType("TOPIC");
        f.setSimilarityScore(0.85);
        f.setStatus("PENDING");
        f.setCreatedAt(Instant.now());
        return f;
    }

    @Test
    void merge_happyPath_callsMergeNodesAndSetsStatusMerged() {
        UUID flagId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID similarId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, candidateId, similarId);

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.merge(flagId);

        verify(graphRepository).mergeNodes(candidateId, similarId);
        verify(flagRepository).save(flag);
        assertThat(flag.getStatus()).isEqualTo("MERGED");
    }

    @Test
    void merge_nonPendingFlag_throwsConflict() {
        UUID flagId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, UUID.randomUUID(), UUID.randomUUID());
        flag.setStatus("DISMISSED");

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));

        assertThatThrownBy(() -> service.merge(flagId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PENDING");

        verify(graphRepository, never()).mergeNodes(any(), any());
    }

    @Test
    void merge_graphThrows_flagUnchanged() {
        UUID flagId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID similarId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, candidateId, similarId);

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        org.mockito.Mockito.doThrow(new RuntimeException("AGE error"))
                .when(graphRepository)
                .mergeNodes(candidateId, similarId);

        assertThatThrownBy(() -> service.merge(flagId)).isInstanceOf(RuntimeException.class);

        // Flag save must NOT have been called — transaction rolled back
        verify(flagRepository, never()).save(any());
        assertThat(flag.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void dismiss_happyPath_setsStatusDismissed() {
        UUID flagId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, UUID.randomUUID(), UUID.randomUUID());

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.dismiss(flagId);

        verify(flagRepository).save(flag);
        assertThat(flag.getStatus()).isEqualTo("DISMISSED");
        verify(graphRepository, never()).mergeNodes(any(), any());
    }

    @Test
    void dismiss_nonPendingFlag_throwsConflict() {
        UUID flagId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, UUID.randomUUID(), UUID.randomUUID());
        flag.setStatus("MERGED");

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));

        assertThatThrownBy(() -> service.dismiss(flagId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PENDING");
    }
}
