package io.emcip.tdlib.adapter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.emcip.tdlib.adapter.model.TelegramMessageEvent;
import io.emcip.tdlib.adapter.model.TelegramUpdateEvent;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TelegramEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelegramEventPublisher.class);
    private static final String TOPIC_TELEGRAM_RAW = "telegram.raw.messages";
    private static final String TOPIC_TELEGRAM_UPDATES = "telegram.raw.updates";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private final Cache<String, Boolean> deduplicationCache =
            Caffeine.newBuilder()
                    .expireAfterWrite(60, TimeUnit.SECONDS)
                    .maximumSize(10_000)
                    .build();

    public TelegramEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public Mono<Void> publishMessage(TdApi.Message message, TdApi.UpdateNewMessage update) {
        String dedupKey = message.chatId + ":" + message.id;
        AtomicBoolean shouldPublish = new AtomicBoolean(false);
        deduplicationCache.get(
                dedupKey,
                k -> {
                    shouldPublish.set(true);
                    return Boolean.TRUE;
                });
        if (!shouldPublish.get()) {
            log.debug(
                    "Skipping duplicate message chatId={} messageId={}",
                    message.chatId,
                    message.id);
            return Mono.empty();
        }

        return Mono.fromCallable(
                        () -> {
                            TelegramMessageEvent event = convertToEvent(message, update);
                            String json = serialize(event);
                            return kafkaTemplate.send(
                                    TOPIC_TELEGRAM_RAW, String.valueOf(message.chatId), json);
                        })
                .flatMap(future -> Mono.fromFuture(future.toCompletableFuture()))
                .doOnSuccess(result -> log.debug("Published message {} to Kafka", message.id))
                .doOnError(
                        e ->
                                log.error(
                                        "Failed to publish message {}: {}",
                                        message.id,
                                        e.getMessage()))
                .onErrorResume(
                        e -> {
                            log.error("Error publishing message to Kafka: {}", e.getMessage(), e);
                            return Mono.empty();
                        })
                .then();
    }

    public Mono<Void> publishUpdate(TdApi.Update update) {
        return Mono.fromCallable(
                        () -> {
                            TelegramUpdateEvent event =
                                    new TelegramUpdateEvent(
                                            UUID.randomUUID().toString(),
                                            update.getClass().getSimpleName(),
                                            update.getConstructor(),
                                            Instant.now());
                            String json = serialize(event);

                            return kafkaTemplate.send(
                                    TOPIC_TELEGRAM_UPDATES,
                                    update.getClass().getSimpleName(),
                                    json);
                        })
                .flatMap(future -> Mono.fromFuture(future.toCompletableFuture()))
                .doOnSuccess(
                        result ->
                                log.debug(
                                        "Published update {} to Kafka",
                                        update.getClass().getSimpleName()))
                .onErrorResume(
                        e -> {
                            log.error("Error publishing update to Kafka: {}", e.getMessage());
                            return Mono.empty();
                        })
                .then();
    }

    private TelegramMessageEvent convertToEvent(
            TdApi.Message message, TdApi.UpdateNewMessage update) {
        String text = "";
        if (message.content instanceof TdApi.MessageText messageText) {
            text = messageText.text.text;
        }

        return new TelegramMessageEvent(
                UUID.randomUUID().toString(),
                message.id,
                message.chatId,
                message.senderId != null ? getSenderId(message.senderId) : null,
                getSenderType(message.senderId),
                text,
                message.date,
                message.editDate,
                message.isOutgoing,
                extractReplyToMessageId(message),
                extractReplyInChatId(message),
                extractMetadata(message),
                Instant.now().toString());
    }

    private long extractReplyToMessageId(TdApi.Message message) {
        if (message.replyTo instanceof TdApi.MessageReplyToMessage reply) {
            return reply.messageId;
        }
        return 0L;
    }

    private long extractReplyInChatId(TdApi.Message message) {
        if (message.replyTo instanceof TdApi.MessageReplyToMessage reply) {
            return reply.chatId;
        }
        return 0L;
    }

    private String getSenderId(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser user) {
            return String.valueOf(user.userId);
        } else if (sender instanceof TdApi.MessageSenderChat chat) {
            return String.valueOf(chat.chatId);
        }
        return null;
    }

    private String getSenderType(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser) {
            return "USER";
        } else if (sender instanceof TdApi.MessageSenderChat) {
            return "CHAT";
        }
        return "UNKNOWN";
    }

    private java.util.Map<String, Object> extractMetadata(TdApi.Message message) {
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();

        if (message.content instanceof TdApi.MessageText messageText) {
            metadata.put("textLength", messageText.text.text.length());
            if (messageText.text.entities != null) {
                metadata.put("entityCount", messageText.text.entities.length);
            }
        }

        metadata.put("isChannelPost", message.isChannelPost);

        return metadata;
    }

    private String serialize(Object event) throws JacksonException {
        return objectMapper.writeValueAsString(event);
    }
}
