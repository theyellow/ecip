package io.emcip.admin.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AdminAuditPublisherTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private AdminAuditPublisher publisher;

    @SuppressWarnings("unchecked")
    @Test
    void publish_sendsToAuditEventsTopic() {
        UUID tenantId = UUID.randomUUID();
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);

        publisher.publish(
                "USER_CREATED", "User", "42", "admin", tenantId, Map.of("username", "newuser"));

        Mockito.verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("audit.events");
        assertThat(record.key()).isEqualTo("42");
        String json = record.value();
        assertThat(json).contains("USER_CREATED");
        assertThat(json).contains("\"actor\":\"admin\"");
        assertThat(json).contains("\"resourceType\":\"User\"");
    }

    @SuppressWarnings("unchecked")
    @Test
    void publish_stampsTenantHeader_andCarriesOutcome() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);

        UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000009");
        publisher.publish(
                "LOGIN_FAILURE",
                "Session",
                "bob",
                "bob",
                tenant,
                Map.of("reason", "BAD_PASSWORD"),
                "FAILURE");

        Mockito.verify(kafkaTemplate).send(captor.capture());
        var record = captor.getValue();
        assertThat(record.topic()).isEqualTo("audit.events");
        var header = record.headers().lastHeader("tenant_id");
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo(tenant.toString());
        assertThat(record.value()).contains("\"outcome\":\"FAILURE\"");
        assertThat(record.value()).contains("\"action\":\"LOGIN_FAILURE\"");
    }

    @SuppressWarnings("unchecked")
    @Test
    void publish_nullTenant_usesGlobalSentinel() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);

        publisher.publish(
                "LOGIN_FAILURE",
                "Session",
                "ghost",
                "ghost",
                null,
                Map.of("reason", "USER_NOT_FOUND"),
                "FAILURE");

        Mockito.verify(kafkaTemplate).send(captor.capture());
        var header = captor.getValue().headers().lastHeader("tenant_id");
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
                .isEqualTo(
                        io.emcip.common.tenant.TenantAwareKafkaSupport.GLOBAL_TENANT_SENTINEL
                                .toString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void sixArgOverload_defaultsOutcomeSuccess() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);

        publisher.publish("USER_CREATED", "User", "u1", "admin", null, Map.of());

        Mockito.verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().value()).contains("\"outcome\":\"SUCCESS\"");
    }
}
