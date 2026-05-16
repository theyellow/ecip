package io.emcip.llm.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmProviderConfigRepositoryTest {

    @Mock LlmProviderConfigRepository repository;

    @Test
    void findFirstByActiveTrueOrderByUpdatedAtDesc_returnsActiveConfig() {
        LlmProviderConfig config =
                LlmProviderConfig.builder()
                        .name("test-provider")
                        .baseUrl("http://localhost:4000")
                        .active(true)
                        .build();
        when(repository.findFirstByActiveTrueOrderByUpdatedAtDesc())
                .thenReturn(Optional.of(config));

        Optional<LlmProviderConfig> found = repository.findFirstByActiveTrueOrderByUpdatedAtDesc();

        assertThat(found).isPresent();
        assertThat(found.get().getBaseUrl()).isEqualTo("http://localhost:4000");
    }

    @Test
    void findFirstByActiveTrueOrderByUpdatedAtDesc_emptyWhenNoneActive() {
        when(repository.findFirstByActiveTrueOrderByUpdatedAtDesc()).thenReturn(Optional.empty());

        Optional<LlmProviderConfig> found = repository.findFirstByActiveTrueOrderByUpdatedAtDesc();

        assertThat(found).isEmpty();
    }
}
