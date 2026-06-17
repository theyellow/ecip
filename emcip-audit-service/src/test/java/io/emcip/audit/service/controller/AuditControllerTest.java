package io.emcip.audit.service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.service.AuditService;
import io.emcip.common.pagination.PageResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    @Mock private AuditService auditService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new AuditController(auditService)).build();
    }

    @Test
    void getEvents_withCorrelationId_returnsMatchingEvents() {
        AuditEventEntity e = new AuditEventEntity();
        e.setEventId("cls-001");
        e.setCorrelationId("evt-root");
        when(auditService.findByCorrelationId("evt-root")).thenReturn(Flux.just(e));

        client.get()
                .uri("/api/audit/events?correlationId=evt-root")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].correlationId")
                .isEqualTo("evt-root")
                .jsonPath("$.total")
                .isEqualTo(1);
    }

    @Test
    void getEvents_returnsPageResponse() {
        AuditEventEntity e = new AuditEventEntity();
        e.setEventId("ev-1");
        PageResponse<AuditEventEntity> page = new PageResponse<>(List.of(e), 1L, 0, 50);
        when(auditService.findPage(any(), any(), eq(0), eq(50), eq(null)))
                .thenReturn(Mono.just(page));

        client.get()
                .uri("/api/audit/events")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.items[0].eventId")
                .isEqualTo("ev-1");
    }
}
