package io.emcip.admin.api.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class VendorApiKeyControllerTest {

    @Mock VendorApiKeyService service;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client =
                WebTestClient.bindToController(new VendorApiKeyController(service))
                        .controllerAdvice(
                                new GlobalExceptionHandler(
                                        org.mockito.Mockito.mock(
                                                io.emcip.admin.api.audit.AdminAuditPublisher
                                                        .class)))
                        .build();
    }

    @Test
    void listGlobal_returns200() {
        VendorApiKeyResponse resp =
                new VendorApiKeyResponse(
                        UUID.randomUUID(), "exa", null, "••••••••1234", true, null);
        when(service.listGlobal()).thenReturn(Flux.just(resp));

        client.get()
                .uri("/api/v1/admin/integrations/keys")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(VendorApiKeyResponse.class)
                .hasSize(1);
    }

    @Test
    void create_returns201() {
        VendorApiKeyRequest req = new VendorApiKeyRequest("exa", "my-key-abcd", true);
        VendorApiKeyResponse resp =
                new VendorApiKeyResponse(
                        UUID.randomUUID(), "exa", null, "••••••••abcd", true, null);
        when(service.createGlobal(any())).thenReturn(Mono.just(resp));

        client.post()
                .uri("/api/v1/admin/integrations/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void delete_returns204() {
        UUID id = UUID.randomUUID();
        when(service.delete(id)).thenReturn(Mono.empty());

        client.delete()
                .uri("/api/v1/admin/integrations/keys/{id}", id)
                .exchange()
                .expectStatus()
                .isNoContent();
    }
}
