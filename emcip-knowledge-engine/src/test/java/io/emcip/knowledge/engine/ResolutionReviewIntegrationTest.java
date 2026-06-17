package io.emcip.knowledge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.net.http.HttpClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@IntegrationTest
class ResolutionReviewIntegrationTest {

    @LocalServerPort private int port;

    @Autowired private ResolutionFlagRepository flagRepository;

    @MockitoBean private GraphRepository graphRepository;

    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        flagRepository.deleteAll();
        // JdkClientHttpRequestFactory uses java.net.http.HttpClient which supports PATCH
        restTemplate =
                new RestTemplate(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()));
        // Disable default error handler so we can assert 4xx/5xx status codes directly
        restTemplate.setErrorHandler(
                new DefaultResponseErrorHandler() {
                    @Override
                    public boolean hasError(ClientHttpResponse response) {
                        return false;
                    }
                });
    }

    private ResolutionFlag insertPendingFlag(UUID tenantId) {
        ResolutionFlag f = new ResolutionFlag();
        f.setCandidateLabel("AI");
        f.setCandidateNodeId(UUID.randomUUID());
        f.setSimilarLabel("artificial intelligence");
        f.setSimilarNodeId(UUID.randomUUID());
        f.setConceptType("TOPIC");
        f.setSimilarityScore(0.85);
        f.setTenantId(tenantId);
        f.setStatus("PENDING");
        return flagRepository.save(f);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void list_returnsPendingFlags() {
        UUID tenantId = UUID.randomUUID();
        insertPendingFlag(tenantId);

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        baseUrl() + "/api/resolution-review?status=PENDING", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"AI\"");
        assertThat(response.getBody()).contains("totalElements");
    }

    @Test
    void dismiss_setsFlagDismissed() {
        UUID tenantId = UUID.randomUUID();
        ResolutionFlag flag = insertPendingFlag(tenantId);

        ResponseEntity<Void> response =
                restTemplate.exchange(
                        baseUrl() + "/api/resolution-review/" + flag.getId() + "/dismiss",
                        HttpMethod.PATCH,
                        null,
                        Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResolutionFlag updated = flagRepository.findById(flag.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("DISMISSED");
    }

    @Test
    void dismiss_alreadyDismissed_returns409() {
        UUID tenantId = UUID.randomUUID();
        ResolutionFlag flag = insertPendingFlag(tenantId);
        flag.setStatus("DISMISSED");
        flagRepository.save(flag);

        ResponseEntity<Void> response =
                restTemplate.exchange(
                        baseUrl() + "/api/resolution-review/" + flag.getId() + "/dismiss",
                        HttpMethod.PATCH,
                        null,
                        Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
