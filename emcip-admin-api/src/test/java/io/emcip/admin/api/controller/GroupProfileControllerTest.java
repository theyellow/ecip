package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.GroupProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GroupProfileControllerTest {

    @Mock private GroupProfileRepository repository;

    private GroupProfileController controller;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        controller = new GroupProfileController(repository);
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    private GroupProfile profile(Long chatId) {
        return GroupProfile.builder().id(1L).telegramChatId(chatId).name("Test Group").build();
    }

    @Test
    void listAll_returns200() {
        when(repository.findAll()).thenReturn(Flux.just(profile(123L)));

        webTestClient
                .get()
                .uri("/api/groups")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(GroupProfile.class)
                .hasSize(1);
    }

    @Test
    void getByChatId_found_returns200() {
        when(repository.findByTelegramChatId(123L)).thenReturn(Mono.just(profile(123L)));

        webTestClient
                .get()
                .uri("/api/groups/123")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(GroupProfile.class)
                .value(p -> assertThat(p.getTelegramChatId()).isEqualTo(123L));
    }

    @Test
    void getByChatId_notFound_returns404() {
        when(repository.findByTelegramChatId(999L)).thenReturn(Mono.empty());

        webTestClient.get().uri("/api/groups/999").exchange().expectStatus().isNotFound();
    }

    @Test
    void create_setsTimestampsAndReturns201() {
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        GroupProfile request = profile(123L);

        webTestClient
                .post()
                .uri("/api/groups")
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated();

        StepVerifier.create(controller.create(profile(123L)))
                .assertNext(
                        response -> {
                            GroupProfile saved = response.getBody();
                            assertThat(saved).isNotNull();
                            assertThat(saved.getCreatedAt()).isNotNull();
                            assertThat(saved.getUpdatedAt()).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void update_found_returns200() {
        GroupProfile existing = profile(123L);
        when(repository.findByTelegramChatId(123L)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        GroupProfile update =
                GroupProfile.builder()
                        .name("Updated Group")
                        .description("New description")
                        .moderationLevel("HIGH")
                        .autoRespond(true)
                        .welcomeMessage("Welcome!")
                        .build();

        webTestClient
                .put()
                .uri("/api/groups/123")
                .bodyValue(update)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void update_notFound_returns404() {
        when(repository.findByTelegramChatId(999L)).thenReturn(Mono.empty());

        GroupProfile update = GroupProfile.builder().name("Updated Group").build();

        webTestClient
                .put()
                .uri("/api/groups/999")
                .bodyValue(update)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void delete_found_returns204() {
        when(repository.findByTelegramChatId(123L)).thenReturn(Mono.just(profile(123L)));
        when(repository.delete(any())).thenReturn(Mono.empty());

        webTestClient.delete().uri("/api/groups/123").exchange().expectStatus().isNoContent();
    }

    @Test
    void delete_notFound_returns404() {
        when(repository.findByTelegramChatId(999L)).thenReturn(Mono.empty());

        webTestClient.delete().uri("/api/groups/999").exchange().expectStatus().isNotFound();
    }
}
