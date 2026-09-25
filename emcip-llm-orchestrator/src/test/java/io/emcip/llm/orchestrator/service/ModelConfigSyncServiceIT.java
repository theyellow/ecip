package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.llm.orchestrator.TestcontainersInitializer;
import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import java.time.Duration;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the sync against real JPA and PostgreSQL.
 *
 * <p>{@link ModelConfigSyncServiceTest} mocks the repositories, so it cannot see how Spring Data
 * decides between {@code persist} and {@code merge}. That blind spot hid a defect that made the
 * sync unable to create any row at all: a new entity carrying a non-null {@code @Version} is
 * treated as existing, merged, and rejected by Hibernate as "already updated or deleted by another
 * transaction". Found on the live cluster on 2026-09-24, where every create failed.
 */
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
class ModelConfigSyncServiceIT {

    @Autowired ModelConfigRepository modelConfigRepository;
    @Autowired LlmProviderConfigRepository providerConfigRepository;
    @Autowired ObjectMapper objectMapper;

    private MockWebServer proxy;

    @BeforeEach
    void setUp() throws Exception {
        // The schema now comes from Liquibase, seeds included: seeded model configs (providers
        // 'litellm'/'anthropic') are referenced by seeded prompt templates, so only the rows this
        // sync owns are cleared, and only those are asserted on.
        modelConfigRepository.deleteAll(
                modelConfigRepository.findAll().stream()
                        .filter(m -> ModelConfigSyncService.PROVIDER_NAME.equals(m.getProvider()))
                        .toList());
        providerConfigRepository.deleteAll();
        proxy = new MockWebServer();
        proxy.start();
        providerConfigRepository.save(
                LlmProviderConfig.builder()
                        .name(ModelConfigSyncService.PROVIDER_NAME)
                        .baseUrl(proxy.url("").toString().replaceAll("/$", ""))
                        .apiKey("sk-test-proxy-key")
                        .active(true)
                        .build());
    }

    @AfterEach
    void tearDown() throws Exception {
        proxy.close();
    }

    @Test
    void createsARowForEachServedModelThatIsNotConfiguredYet() {
        proxy.enqueue(
                new MockResponse.Builder()
                        .body("{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}")
                        .addHeader("Content-Type", "application/json")
                        .build());

        new ModelConfigSyncService(
                        modelConfigRepository,
                        providerConfigRepository,
                        objectMapper,
                        Duration.ofSeconds(5))
                .run(null);

        assertThat(
                        modelConfigRepository.findAll().stream()
                                .filter(
                                        m ->
                                                ModelConfigSyncService.PROVIDER_NAME.equals(
                                                        m.getProvider()))
                                .toList())
                .extracting(ModelConfig::getModelName, ModelConfig::getProvider)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "model-a", ModelConfigSyncService.PROVIDER_NAME),
                        org.assertj.core.groups.Tuple.tuple(
                                "model-b", ModelConfigSyncService.PROVIDER_NAME));
    }
}
