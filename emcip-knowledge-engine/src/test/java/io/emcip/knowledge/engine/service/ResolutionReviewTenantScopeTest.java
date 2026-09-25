package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/** KNOW-F1: resolution flags — list own + global, merge/dismiss own only. */
@IntegrationTest
class ResolutionReviewTenantScopeTest {

    @Autowired ResolutionReviewService service;
    @Autowired ResolutionFlagRepository flags;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final String conceptType = "KnowF1Probe" + UUID.randomUUID().toString().substring(0, 8);

    private ResolutionFlag flag(UUID tenant) {
        ResolutionFlag f = new ResolutionFlag();
        f.setCandidateLabel("cand");
        f.setCandidateNodeId(UUID.randomUUID());
        f.setSimilarLabel("sim");
        f.setSimilarNodeId(UUID.randomUUID());
        f.setConceptType(conceptType);
        f.setSimilarityScore(0.9);
        f.setTenantId(tenant);
        return flags.save(f);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode().value())
                                        .isEqualTo(404));
    }

    @Test
    void listIsOwnPlusGlobalWithATenant() {
        ResolutionFlag own = flag(tenantA);
        ResolutionFlag global = flag(null);
        ResolutionFlag other = flag(tenantB);

        var ids =
                service
                        .list(null, conceptType, tenantA, PageRequest.of(0, 50))
                        .getContent()
                        .stream()
                        .map(ResolutionFlag::getId)
                        .toList();

        assertThat(ids).contains(own.getId(), global.getId()).doesNotContain(other.getId());
    }

    @Test
    void cannotDismissOrMergeAnotherTenantsOrAGlobalFlag() {
        ResolutionFlag other = flag(tenantB);
        ResolutionFlag global = flag(null);

        assertNotFound(() -> service.dismiss(other.getId(), tenantA));
        assertNotFound(() -> service.dismiss(global.getId(), tenantA));
        assertNotFound(() -> service.merge(other.getId(), tenantA));
        assertThat(flags.findById(other.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(flags.findById(global.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void canDismissOwnFlag() {
        ResolutionFlag own = flag(tenantA);

        service.dismiss(own.getId(), tenantA);

        assertThat(flags.findById(own.getId()).orElseThrow().getStatus()).isEqualTo("DISMISSED");
    }
}
