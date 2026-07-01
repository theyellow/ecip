package io.emcip.admin.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AdminAuditPublisherTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private AdminAuditPublisher publisher;

    @Captor private ArgumentCaptor<String> valueCaptor;

    @Test
    void publish_sendsToAuditEventsTopic() {
        UUID tenantId = UUID.randomUUID();

        publisher.publish(
                "USER_CREATED", "User", "42", "admin", tenantId, Map.of("username", "newuser"));

        verify(kafkaTemplate).send(eq("audit.events"), eq("42"), valueCaptor.capture());
        String json = valueCaptor.getValue();
        assertThat(json).contains("USER_CREATED");
        assertThat(json).contains("\"actor\":\"admin\"");
        assertThat(json).contains("\"resourceType\":\"User\"");
    }
}
