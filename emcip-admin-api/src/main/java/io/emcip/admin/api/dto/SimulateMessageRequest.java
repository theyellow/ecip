package io.emcip.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Request to inject a simulated Telegram message into the pipeline")
@Data
public class SimulateMessageRequest {

    @NotNull(message = "chatId is required")
    @Schema(description = "Telegram chat ID", example = "-1001234567890")
    private Long chatId;

    @Size(max = 100, message = "senderId must be 100 characters or fewer")
    @Schema(description = "Sender identifier", example = "user-42")
    private String senderId;

    @Pattern(regexp = "^(USER|BOT)$", message = "senderType must be USER or BOT")
    @Schema(
            description = "Sender type",
            example = "USER",
            allowableValues = {"USER", "BOT"})
    private String senderType;

    @Size(max = 4096, message = "text must be 4096 characters or fewer")
    @Schema(description = "Message text to classify and process", example = "Hello everyone!")
    private String text;

    @Schema(description = "Optional Telegram message ID override")
    private Long telegramMessageId;
}
