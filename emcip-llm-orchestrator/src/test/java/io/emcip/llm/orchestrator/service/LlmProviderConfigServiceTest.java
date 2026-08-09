package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class LlmProviderConfigServiceTest {

    @Mock LlmProviderConfigRepository repository;
    @Mock ObjectMapper objectMapper;
    @InjectMocks LlmProviderConfigService service;

    @Test
    void getActiveProvider_delegatesToRepository() {
        LlmProviderConfig config =
                LlmProviderConfig.builder()
                        .name("test")
                        .baseUrl("http://localhost:4000")
                        .active(true)
                        .build();
        when(repository.findFirstByActiveTrueOrderByUpdatedAtDesc())
                .thenReturn(Optional.of(config));

        Optional<LlmProviderConfig> result = service.getActiveProvider();

        assertThat(result).isPresent();
        assertThat(result.get().getBaseUrl()).isEqualTo("http://localhost:4000");
    }

    @Test
    void saveProvider_deactivatesOthersBeforeSavingActiveConfig() {
        LlmProviderConfig incoming =
                LlmProviderConfig.builder()
                        .name("new")
                        .baseUrl("http://new:4000")
                        .active(true)
                        .build();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveProvider(incoming);

        // One bulk update, not a load-modify-save loop: loading the other rows would
        // decrypt their keys and fail on any row that predates secrets encryption.
        verify(repository).deactivateAllExcept(eq(null), any(Instant.class));
        verify(repository, times(1)).save(any());
        verify(repository, never()).findAll();
    }

    @Test
    void saveProvider_doesNotDeactivateSelfWhenReactivatingExistingProvider() {
        UUID id = UUID.randomUUID();
        LlmProviderConfig config =
                LlmProviderConfig.builder()
                        .id(id)
                        .name("provider")
                        .baseUrl("http://litellm:4000")
                        .active(true)
                        .build();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LlmProviderConfig result = service.saveProvider(config);

        // must remain active — self-deactivation was the bug, and the id is what excludes it
        assertThat(result.getActive()).isTrue();
        verify(repository).deactivateAllExcept(eq(id), any(Instant.class));
    }

    @Test
    void saveProvider_doesNotDeactivateOthersWhenSavingInactiveConfig() {
        LlmProviderConfig incoming =
                LlmProviderConfig.builder()
                        .name("new")
                        .baseUrl("http://new:4000")
                        .active(false)
                        .build();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveProvider(incoming);

        verify(repository, times(1)).save(any());
        verify(repository, never()).deactivateAllExcept(any(), any());
    }

    @Test
    void updateProvider_returnsEmptyForUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findSummaryById(id)).thenReturn(Optional.empty());

        assertThat(service.updateProvider(id, "n", "http://x", false, "sk-key")).isEmpty();
        verify(repository, never()).updateDetails(any(), any(), any(), any(), any());
        verify(repository, never()).updateApiKey(any(), any(), any());
    }

    @Test
    void updateProvider_leavesStoredKeyAloneWhenNoneSupplied() {
        UUID id = UUID.randomUUID();
        LlmProviderConfigRepository.Summary summary =
                mock(LlmProviderConfigRepository.Summary.class);
        when(repository.findSummaryById(id)).thenReturn(Optional.of(summary));

        service.updateProvider(id, "n", "http://x", false, null);

        verify(repository).updateDetails(eq(id), eq("n"), eq("http://x"), eq(false), any());
        verify(repository, never()).updateApiKey(any(), any(), any());
    }

    @Test
    void updateProvider_writesReplacementKeyWhenSupplied() {
        UUID id = UUID.randomUUID();
        LlmProviderConfigRepository.Summary summary =
                mock(LlmProviderConfigRepository.Summary.class);
        when(repository.findSummaryById(id)).thenReturn(Optional.of(summary));

        service.updateProvider(id, "n", "http://x", false, "sk-replacement");

        verify(repository).updateApiKey(eq(id), eq("sk-replacement"), any(Instant.class));
    }
}
