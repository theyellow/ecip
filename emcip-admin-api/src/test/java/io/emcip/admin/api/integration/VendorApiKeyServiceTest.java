package io.emcip.admin.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.common.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class VendorApiKeyServiceTest {

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Mock private VendorApiKeyRowRepository repo;

    private final SecretCipher cipher = new SecretCipher(KEY);

    private VendorApiKeyService service() {
        return new VendorApiKeyService(repo, cipher);
    }

    @Test
    void createGlobal_encryptsBeforePersisting() {
        when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(
                        service()
                                .createGlobal(
                                        new VendorApiKeyRequest("brave", "sk-plaintext", true)))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<VendorApiKeyRow> captor = ArgumentCaptor.forClass(VendorApiKeyRow.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());

        String persisted = captor.getValue().getApiKey();
        assertThat(persisted).startsWith("v1:");
        assertThat(persisted).doesNotContain("sk-plaintext");
        assertThat(cipher.decrypt(persisted, "test")).isEqualTo("sk-plaintext");
    }

    @Test
    void listGlobal_masksTheDecryptedKeyNotTheCiphertext() {
        VendorApiKeyRow row =
                VendorApiKeyRow.builder()
                        .id(UUID.randomUUID())
                        .vendorId("brave")
                        .apiKey(cipher.encrypt("sk-abcdefgh-TAIL"))
                        .enabled(true)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repo.findAllByTenantIdIsNull()).thenReturn(reactor.core.publisher.Flux.just(row));

        StepVerifier.create(service().listGlobal())
                .assertNext(
                        response -> {
                            // Last 4 chars of the PLAINTEXT, not of the base64 ciphertext.
                            assertThat(response.maskedKey()).endsWith("TAIL");
                            assertThat(response.maskedKey()).doesNotContain("v1:");
                        })
                .verifyComplete();
    }

    @Test
    void update_reEncryptsTheReplacementKey() {
        VendorApiKeyRow existing =
                VendorApiKeyRow.builder()
                        .id(UUID.randomUUID())
                        .vendorId("exa")
                        .apiKey(cipher.encrypt("sk-old"))
                        .enabled(true)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repo.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(
                        service()
                                .update(
                                        existing.getId(),
                                        new VendorApiKeyRequest("exa", "sk-new", true)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(existing.getApiKey()).startsWith("v1:");
        assertThat(cipher.decrypt(existing.getApiKey(), "test")).isEqualTo("sk-new");
    }
}
