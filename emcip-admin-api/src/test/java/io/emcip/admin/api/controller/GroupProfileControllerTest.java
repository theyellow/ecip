package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.service.GroupProfileService;
import io.emcip.common.tenant.TenantContext;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class GroupProfileControllerTest {

    @Mock private GroupProfileService service;

    private GroupProfileController controller;
    private WebTestClient webTestClient;

    private static final WebFilter ADMIN_MODE_FILTER =
            (exchange, chain) -> {
                TenantContext.setAdminMode(true);
                return chain.filter(exchange).doFinally(s -> TenantContext.clear());
            };

    @BeforeEach
    void setUp() {
        TenantContext.setAdminMode(true);
        controller = new GroupProfileController(service);
        webTestClient =
                WebTestClient.bindToController(controller).webFilter(ADMIN_MODE_FILTER).build();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private GroupProfile profile(Long chatId) {
        return GroupProfile.builder().id(1L).telegramChatId(chatId).name("Test Group").build();
    }

    @Test
    void listAll_returns200() {
        when(service.findAll()).thenReturn(Flux.just(profile(123L)));

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
        when(service.findByChatId(123L)).thenReturn(Mono.just(profile(123L)));

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
        when(service.findByChatId(999L))
                .thenReturn(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Group not found: 999")));

        webTestClient.get().uri("/api/groups/999").exchange().expectStatus().isNotFound();
    }

    @Test
    void create_setsTimestampsAndReturns201() {
        GroupProfile saved =
                GroupProfile.builder()
                        .id(1L)
                        .telegramChatId(123L)
                        .name("Test Group")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(service.create(any())).thenReturn(Mono.just(saved));

        webTestClient
                .post()
                .uri("/api/groups")
                .bodyValue(profile(123L))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(GroupProfile.class)
                .value(
                        p -> {
                            assertThat(p.getCreatedAt()).isNotNull();
                            assertThat(p.getUpdatedAt()).isNotNull();
                        });
    }

    @Test
    void update_found_returns200() {
        GroupProfile updated =
                GroupProfile.builder()
                        .id(1L)
                        .telegramChatId(123L)
                        .name("Updated Group")
                        .description("New description")
                        .moderationLevel("HIGH")
                        .autoRespond(true)
                        .welcomeMessage("Welcome!")
                        .build();
        when(service.update(eq(123L), any())).thenReturn(Mono.just(updated));

        GroupProfile patch =
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
                .bodyValue(patch)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void update_notFound_returns404() {
        when(service.update(eq(999L), any()))
                .thenReturn(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Group not found: 999")));

        GroupProfile patch = GroupProfile.builder().name("Updated Group").build();

        webTestClient
                .put()
                .uri("/api/groups/999")
                .bodyValue(patch)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void delete_found_returns204() {
        when(service.delete(123L)).thenReturn(Mono.empty());

        webTestClient.delete().uri("/api/groups/123").exchange().expectStatus().isNoContent();
    }

    @Test
    void delete_notFound_returns404() {
        when(service.delete(999L))
                .thenReturn(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Group not found: 999")));

        webTestClient.delete().uri("/api/groups/999").exchange().expectStatus().isNotFound();
    }
}
