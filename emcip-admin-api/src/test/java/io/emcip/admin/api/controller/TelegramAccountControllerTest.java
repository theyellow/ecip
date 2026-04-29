package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramAccountControllerTest {

    @Mock TelegramAccountRepository repository;
    @Mock R2dbcEntityTemplate r2dbcEntityTemplate;
    @Mock WebClient tdlibClient;

    TelegramAccountController controller;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        controller =
                new TelegramAccountController(
                        repository, r2dbcEntityTemplate, tdlibClient, 12345, "abc123");
    }

    @Test
    void listAccounts_returnsMaskedSessionString() {
        UUID id = UUID.randomUUID();
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(id)
                        .phoneNumber("+49123456789")
                        .apiId(12345)
                        .apiHash("abc123")
                        .displayName("Monitor 1")
                        .sessionString("secret-session-data")
                        .status(TelegramAccountStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        when(repository.findAll()).thenReturn(Flux.just(account));

        StepVerifier.create(controller.listAccounts())
                .assertNext(
                        list -> {
                            assertThat(list).hasSize(1);
                            assertThat(list.get(0).get("sessionStringSet")).isEqualTo(true);
                            assertThat(list.get(0)).doesNotContainKey("sessionString");
                        })
                .verifyComplete();
    }

    @Test
    void createAccount_savesWithUnconfiguredStatus() {
        when(r2dbcEntityTemplate.insert(any(TelegramAccount.class)))
                .thenAnswer(
                        inv -> {
                            TelegramAccount a = inv.getArgument(0);
                            return Mono.just(a);
                        });

        TelegramAccountController.CreateAccountRequest req =
                new TelegramAccountController.CreateAccountRequest("+49123456789", "Monitor 1");

        StepVerifier.create(controller.createAccount(req))
                .assertNext(
                        map -> {
                            assertThat(map.get("status")).isEqualTo("UNCONFIGURED");
                            assertThat(map.get("phoneNumber")).isEqualTo("+49123456789");
                            assertThat(map).doesNotContainKey("apiHash");
                            assertThat(map).doesNotContainKey("sessionString");
                        })
                .verifyComplete();
    }
}
