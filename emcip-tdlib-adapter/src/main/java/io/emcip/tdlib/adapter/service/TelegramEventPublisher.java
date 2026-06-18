package io.emcip.tdlib.adapter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.emcip.common.events.EventSchemas;
import io.emcip.tdlib.adapter.model.TelegramUpdateEvent;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TelegramEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelegramEventPublisher.class);
    private static final String TOPIC_TELEGRAM_RAW = "telegram.raw.messages";
    private static final String TOPIC_KNOWLEDGE_RAW = "knowledge.raw.messages";
    private static final String TOPIC_TELEGRAM_UPDATES = "telegram.raw.updates";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ProfileCacheService profileCache;
    private final ObjectMapper objectMapper;

    @Value("${app.tenant-id:}")
    private String configuredTenantId;

    private final Cache<String, Boolean> deduplicationCache =
            Caffeine.newBuilder()
                    .expireAfterWrite(60, TimeUnit.SECONDS)
                    .maximumSize(10_000)
                    .build();

    public TelegramEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate, ProfileCacheService profileCache) {
        this.kafkaTemplate = kafkaTemplate;
        this.profileCache = profileCache;
        this.objectMapper = new ObjectMapper();
    }

    public Mono<Void> publishMessage(
            TdApi.Message message,
            TdApi.UpdateNewMessage update,
            String tenantId,
            boolean knowledgeFork) {
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
                            EventSchemas.TelegramMessageEvent event =
                                    convertToEvent(message, update);
                            String json = serialize(event);
                            org.apache.kafka.clients.producer.ProducerRecord<String, String>
                                    kafkaRecord =
                                            new org.apache.kafka.clients.producer.ProducerRecord<>(
                                                    TOPIC_TELEGRAM_RAW,
                                                    String.valueOf(message.chatId),
                                                    json);
                            String effectiveTenantId =
                                    (tenantId != null && !tenantId.isBlank())
                                            ? tenantId
                                            : (configuredTenantId != null
                                                            && !configuredTenantId.isBlank()
                                                    ? configuredTenantId
                                                    : null);
                            if (effectiveTenantId != null) {
                                kafkaRecord
                                        .headers()
                                        .add(
                                                io.emcip.common.tenant.TenantContext.KAFKA_HEADER,
                                                effectiveTenantId.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8));
                            }
                            if (knowledgeFork) {
                                org.apache.kafka.clients.producer.ProducerRecord<String, String>
                                        knowledgeRecord =
                                                new org.apache.kafka.clients.producer
                                                        .ProducerRecord<>(
                                                        TOPIC_KNOWLEDGE_RAW,
                                                        String.valueOf(message.chatId),
                                                        json);
                                if (effectiveTenantId != null) {
                                    knowledgeRecord
                                            .headers()
                                            .add(
                                                    io.emcip.common.tenant.TenantContext
                                                            .KAFKA_HEADER,
                                                    effectiveTenantId.getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
                                }
                                kafkaTemplate.send(knowledgeRecord);
                            }
                            return kafkaTemplate.send(kafkaRecord);
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

    private EventSchemas.TelegramMessageEvent convertToEvent(
            TdApi.Message message, TdApi.UpdateNewMessage update) {
        String text = "";
        if (message.content instanceof TdApi.MessageText messageText) {
            text = messageText.text.text;
        }

        long senderId = message.senderId != null ? getSenderIdNumeric(message.senderId) : 0L;

        return new EventSchemas.TelegramMessageEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                null,
                null,
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
                Instant.now().toString(),
                senderId != 0 ? profileCache.getUserDisplayName(senderId) : null,
                senderId != 0 ? profileCache.getUserUsername(senderId) : null,
                profileCache.getChatTitle(message.chatId));
    }

    private long getSenderIdNumeric(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser user) {
            return user.userId;
        } else if (sender instanceof TdApi.MessageSenderChat chat) {
            return chat.chatId;
        }
        return 0L;
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

        String contentType =
                switch (message.content) {
                    case TdApi.MessageText ignored -> "text";
                    case TdApi.MessageSticker ignored -> "sticker";
                    case TdApi.MessagePhoto ignored -> "photo";
                    case TdApi.MessageVideo ignored -> "video";
                    case TdApi.MessageAnimation ignored -> "animation";
                    case TdApi.MessageDocument ignored -> "document";
                    case TdApi.MessageAudio ignored -> "audio";
                    case TdApi.MessageVoiceNote ignored -> "voice";
                    case TdApi.MessageVideoNote ignored -> "video_note";
                    case TdApi.MessagePoll ignored -> "poll";
                    default -> "other";
                };
        metadata.put("contentType", contentType);

        return metadata;
    }

    private String serialize(Object event) throws JacksonException {
        return objectMapper.writeValueAsString(event);
    }
}
