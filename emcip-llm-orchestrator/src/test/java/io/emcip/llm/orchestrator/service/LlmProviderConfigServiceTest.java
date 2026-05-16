package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import java.util.List;
import java.util.Optional;
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
        LlmProviderConfig existing =
                LlmProviderConfig.builder()
                        .name("old")
                        .baseUrl("http://old:4000")
                        .active(true)
                        .build();
        when(repository.findAll()).thenReturn(List.of(existing));
        LlmProviderConfig incoming =
                LlmProviderConfig.builder()
                        .name("new")
                        .baseUrl("http://new:4000")
                        .active(true)
                        .build();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveProvider(incoming);

        // existing was deactivated and saved, then incoming was saved
        verify(repository, times(2)).save(any());
        assertThat(existing.getActive()).isFalse();
    }

    @Test
    void saveProvider_doesNotDeactivateOthersWhenSavingInactiveConfig() {
        LlmProviderConfig existing =
                LlmProviderConfig.builder()
                        .name("old")
                        .baseUrl("http://old:4000")
                        .active(true)
                        .build();
        LlmProviderConfig incoming =
                LlmProviderConfig.builder()
                        .name("new")
                        .baseUrl("http://new:4000")
                        .active(false)
                        .build();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveProvider(incoming);

        // only incoming was saved — existing untouched
        verify(repository, times(1)).save(any());
        assertThat(existing.getActive()).isTrue();
    }
}
