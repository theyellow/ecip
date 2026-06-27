package io.emcip.intent.classifier.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.emcip.intent.classifier.entity.IntentRule;
import io.emcip.intent.classifier.repository.IntentRuleRepository;
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
class IntentRuleControllerTest {

    @Mock IntentRuleRepository repository;
    @Mock IntentClassificationService classificationService;

    MockMvc mvc;

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new IntentRuleController(repository, classificationService))
                        .setMessageConverters(new MappingJackson2HttpMessageConverter())
                        .build();
    }

    @Test
    void list_returnsRulesForTenant() throws Exception {
        var rule =
                IntentRule.builder()
                        .id("rule-1")
                        .name("Greeting")
                        .matchMode("KEYWORD")
                        .pattern("hello|hi")
                        .intent("GREETING")
                        .confidence(0.8)
                        .priority(10)
                        .active(true)
                        .tenantId(TENANT)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repository.findByTenantIdOrderByPriorityAsc(TENANT)).thenReturn(List.of(rule));
        when(repository.findByTenantIdIsNullOrderByPriorityAsc()).thenReturn(List.of());

        mvc.perform(get("/api/intent-rules").header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Greeting"))
                .andExpect(jsonPath("$[0].intent").value("GREETING"));
    }

    @Test
    void create_returns201() throws Exception {
        var saved =
                IntentRule.builder()
                        .id("new-id")
                        .name("Test")
                        .matchMode("KEYWORD")
                        .pattern("test")
                        .intent("TEST")
                        .confidence(0.8)
                        .priority(100)
                        .active(true)
                        .tenantId(TENANT)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repository.save(any())).thenReturn(saved);

        mvc.perform(
                        post("/api/intent-rules")
                                .header("X-Tenant-Id", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"Test","matchMode":"KEYWORD","pattern":"test",
                                         "intent":"TEST","confidence":0.8,"priority":100}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("new-id"));
    }

    @Test
    void update_returns200() throws Exception {
        var existing =
                IntentRule.builder()
                        .id("rule-1")
                        .name("Old")
                        .matchMode("KEYWORD")
                        .pattern("old")
                        .intent("OLD")
                        .confidence(0.5)
                        .priority(50)
                        .active(true)
                        .tenantId(TENANT)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repository.findById("rule-1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenReturn(existing);

        mvc.perform(
                        put("/api/intent-rules/rule-1")
                                .header("X-Tenant-Id", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
{"name":"Old","matchMode":"KEYWORD","pattern":"old",
 "intent":"OLD","confidence":0.5,"priority":50,"active":true}
"""))
                .andExpect(status().isOk());
    }

    @Test
    void update_wrongTenant_returns404() throws Exception {
        var existing =
                IntentRule.builder()
                        .id("rule-1")
                        .tenantId(UUID.randomUUID())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repository.findById("rule-1")).thenReturn(Optional.of(existing));

        mvc.perform(
                        put("/api/intent-rules/rule-1")
                                .header("X-Tenant-Id", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"x","matchMode":"KEYWORD","pattern":"x",
                                         "intent":"X","confidence":0.5,"priority":1,"active":true}
                                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        var existing =
                IntentRule.builder()
                        .id("rule-1")
                        .tenantId(TENANT)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repository.findById("rule-1")).thenReturn(Optional.of(existing));

        mvc.perform(delete("/api/intent-rules/rule-1").header("X-Tenant-Id", TENANT))
                .andExpect(status().isNoContent());
        verify(repository).delete(existing);
        verify(classificationService).refreshRules();
    }
}
