package io.emcip.tdlib.adapter.model;

import java.util.Map;

public record TelegramMessageEvent(
        String eventId,
        long telegramMessageId,
        long chatId,
        String senderId,
        String senderType,
        String text,
        int date,
        int editDate,
        boolean isOutgoing,
        long replyToMessageId,
        long replyInChatId,
        Map<String, Object> metadata,
        String ingestedAt) {}
