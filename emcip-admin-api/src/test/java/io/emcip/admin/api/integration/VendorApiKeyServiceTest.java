package io.emcip.admin.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class VendorApiKeyServiceTest {

    @Mock private VendorApiKeyRowRepository repo;

    private VendorApiKeyService service;

    @BeforeEach
    void setUp() {
        service = new VendorApiKeyService(repo);
    }

    @Test
    void listGlobal_returnsAllNullTenantRows() {
        VendorApiKeyRow row =
                VendorApiKeyRow.builder()
                        .id(UUID.randomUUID())
                        .vendorId("exa")
                        .apiKey("secret-key-1234")
                        .enabled(true)
                        .build();
        when(repo.findAllByTenantIdIsNull()).thenReturn(Flux.just(row));

        StepVerifier.create(service.listGlobal())
                .assertNext(
                        r -> {
                            assertThat(r.vendorId()).isEqualTo("exa");
                            assertThat(r.maskedKey()).isEqualTo("••••••••1234");
                            assertThat(r.maskedKey()).doesNotContain("secret");
                        })
                .verifyComplete();
    }

    @Test
    void create_savesRowAndReturnsMasked() {
        VendorApiKeyRequest req = new VendorApiKeyRequest("brave", "my-api-key-5678", true);
        VendorApiKeyRow saved =
                VendorApiKeyRow.builder()
                        .id(UUID.randomUUID())
                        .vendorId("brave")
                        .apiKey("my-api-key-5678")
                        .enabled(true)
                        .build();
        when(repo.save(any())).thenReturn(Mono.just(saved));

        StepVerifier.create(service.createGlobal(req))
                .assertNext(
                        r -> {
                            assertThat(r.maskedKey()).endsWith("5678");
                            assertThat(r.maskedKey()).doesNotContain("my-api-key");
                        })
                .verifyComplete();
    }
}
