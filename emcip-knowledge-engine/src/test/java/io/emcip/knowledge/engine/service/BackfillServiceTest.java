package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class BackfillServiceTest {

    @Mock KnowledgeEventPublisher eventPublisher;

    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    BackfillService service;

    @BeforeEach
    void setUp() {
        service =
                new BackfillService(
                        "http://localhost:19999", "test-token", eventPublisher, kafkaTemplate);
    }

    @Test
    void triggerBackfill_returnsNonNullBackfillId() {
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String backfillId =
                service.triggerBackfill(accountId, -1001234567890L, 1_700_000_000L, tenantId);

        assertThat(backfillId).isNotNull().isNotBlank();
        assertThat(UUID.fromString(backfillId)).isNotNull();
    }

    @Test
    void getStatus_returnsRunningForActiveBackfill() {
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String backfillId =
                service.triggerBackfill(accountId, -1001234567890L, 1_700_000_000L, tenantId);

        BackfillService.BackfillStatus status = service.getStatus(backfillId);

        assertThat(status.status()).isEqualTo("RUNNING");
        assertThat(status.backfillId()).isEqualTo(backfillId);
        assertThat(status.chatId()).isEqualTo(-1001234567890L);
        assertThat(status.processed()).isZero();
        assertThat(status.startedAt()).isNotNull();
    }

    @Test
    void getStatus_returnsNotFoundForUnknownId() {
        String unknownId = UUID.randomUUID().toString();

        BackfillService.BackfillStatus status = service.getStatus(unknownId);

        assertThat(status.status()).isEqualTo("NOT_FOUND");
        assertThat(status.backfillId()).isEqualTo(unknownId);
    }
}
