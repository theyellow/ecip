package io.emcip.intent.classifier.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.emcip.intent.classifier.entity.IntentSignalConfig;
import io.emcip.intent.classifier.repository.IntentSignalConfigRepository;
import io.emcip.intent.classifier.service.IntentClassificationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class IntentSignalConfigControllerTest {

    @Mock IntentSignalConfigRepository repository;
    @Mock IntentClassificationService classificationService;

    MockMvc mvc;

    private static final UUID TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new IntentSignalConfigController(repository, classificationService))
                        .setMessageConverters(new MappingJackson2HttpMessageConverter())
                        .build();
    }

    @Test
    void get_returnsConfig() throws Exception {
        var config =
                IntentSignalConfig.builder()
                        .id("cfg-1")
                        .tenantId(TENANT)
                        .foreignScriptRatio(0.6)
                        .cyrillicRatio(0.6)
                        .lookalikeSuspicion(3)
                        .zeroWidthAbuse(2)
                        .capsRatio(0.7)
                        .toxicityWords(List.of("spam"))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.of(config));

        mvc.perform(get("/api/intent-signal-config").header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignScriptRatio").value(0.6))
                .andExpect(jsonPath("$.lookalikeSuspicion").value(3));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.empty());
        when(repository.findByTenantIdIsNull()).thenReturn(Optional.empty());

        mvc.perform(get("/api/intent-signal-config").header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_fallsBackToGlobalConfig() throws Exception {
        var global =
                IntentSignalConfig.builder()
                        .id("cfg-global")
                        .tenantId(null)
                        .foreignScriptRatio(0.5)
                        .cyrillicRatio(0.4)
                        .lookalikeSuspicion(1)
                        .zeroWidthAbuse(0)
                        .capsRatio(0.6)
                        .toxicityWords(List.of())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.empty());
        when(repository.findByTenantIdIsNull()).thenReturn(Optional.of(global));

        mvc.perform(get("/api/intent-signal-config").header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cfg-global"))
                .andExpect(jsonPath("$.lookalikeSuspicion").value(1));
    }

    @Test
    void put_upsertsConfig() throws Exception {
        var saved =
                IntentSignalConfig.builder()
                        .id("cfg-1")
                        .tenantId(TENANT)
                        .foreignScriptRatio(0.5)
                        .cyrillicRatio(0.5)
                        .lookalikeSuspicion(2)
                        .zeroWidthAbuse(1)
                        .capsRatio(0.8)
                        .toxicityWords(List.of("spam", "bad"))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.empty());
        when(repository.save(any())).thenReturn(saved);

        mvc.perform(
                        put("/api/intent-signal-config")
                                .header("X-Tenant-Id", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"foreignScriptRatio":0.5,"cyrillicRatio":0.5,
                                         "lookalikeSuspicion":2,"zeroWidthAbuse":1,"capsRatio":0.8,
                                         "toxicityWords":["spam","bad"]}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookalikeSuspicion").value(2));

        verify(classificationService).refreshSignalConfig();
    }
}
