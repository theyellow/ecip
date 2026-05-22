package io.emcip.admin.api.service;

import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.ReactorTenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimulationService {

    public static final String TOPIC = "telegram.raw.messages";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public record SimulateResult(String eventId, String topic) {}

    public Mono<SimulateResult> simulate(SimulateMessageRequest req) {
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    String eventId = UUID.randomUUID().toString();
                    String timestamp = Instant.now().toString();

                    EventSchemas.TelegramMessageEvent event =
                            new EventSchemas.TelegramMessageEvent(
                                    eventId,
                                    timestamp,
                                    null,
                                    null,
                                    req.getTelegramMessageId() != null
                                            ? req.getTelegramMessageId()
                                            : System.currentTimeMillis(),
                                    req.getChatId(),
                                    req.getSenderId() != null ? req.getSenderId() : "sim-user",
                                    req.getSenderType() != null ? req.getSenderType() : "USER",
                                    req.getText(),
                                    (int) (System.currentTimeMillis() / 1000),
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null);

                    try {
                        String payload = objectMapper.writeValueAsString(event);
                        ProducerRecord<String, String> record =
                                new ProducerRecord<>(
                                        TOPIC, null, String.valueOf(req.getChatId()), payload);
                        if (tenantId != null) {
                            record.headers()
                                    .add("tenant_id", tenantId.getBytes(StandardCharsets.UTF_8));
                        }
                        kafkaTemplate.send(record);
                        return Mono.just(new SimulateResult(eventId, TOPIC));
                    } catch (JacksonException e) {
                        log.error("Failed to serialize simulation event", e);
                        return Mono.<SimulateResult>error(
                                new RuntimeException("Failed to serialize event", e));
                    }
                });
    }
}
