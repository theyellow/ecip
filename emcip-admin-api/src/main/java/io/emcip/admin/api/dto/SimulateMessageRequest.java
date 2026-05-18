package io.emcip.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Request to inject a simulated Telegram message into the pipeline")
@Data
public class SimulateMessageRequest {
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
