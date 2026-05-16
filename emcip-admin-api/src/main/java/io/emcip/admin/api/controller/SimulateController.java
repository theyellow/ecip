package io.emcip.admin.api.controller;

import io.emcip.common.events.EventSchemas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/simulate")
@Tag(name = "Simulation", description = "Inject test messages through the full pipeline")
public class SimulateController {

    private static final String TOPIC = "telegram.raw.messages";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public SimulateController(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Operation(summary = "Simulate a Telegram message through the processing pipeline")
    @PostMapping("/message")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> simulateMessage(@RequestBody SimulateMessageRequest req) {
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
            kafkaTemplate.send(TOPIC, String.valueOf(req.getChatId()), payload);
            return Mono.just(
                    Map.of(
                            "eventId",
                            eventId,
                            "topic",
                            TOPIC,
                            "chatId",
                            req.getChatId(),
                            "status",
                            "published"));
        } catch (JacksonException e) {
            return Mono.error(new RuntimeException("Failed to serialize event", e));
        }
    }

    @Schema(description = "Request to inject a simulated Telegram message into the pipeline")
    @Data
    public static class SimulateMessageRequest {
        @Schema(description = "Telegram chat ID", example = "-1001234567890")
        private Long chatId;

        @Schema(description = "Sender identifier", example = "user-42")
        private String senderId;

        @Schema(
                description = "Sender type",
                example = "USER",
                allowableValues = {"USER", "BOT"})
        private String senderType;

        @Schema(description = "Message text to classify and process", example = "Hello everyone!")
        private String text;

        @Schema(description = "Optional Telegram message ID override")
        private Long telegramMessageId;
    }
}
