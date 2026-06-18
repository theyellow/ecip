package io.emcip.tdlib.adapter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramEventPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;
    TelegramEventPublisher publisher;

    private final ProfileCacheService profileCache = new ProfileCacheService();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        publisher = new TelegramEventPublisher(kafkaTemplate, profileCache);
        SendResult<String, String> sendResult =
                new SendResult<>(null, new RecordMetadata(null, 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    @Test
    void publishMessage_sameMessageTwice_onlySendsOnce() {
        TdApi.UpdateNewMessage update1 = makeUpdate(100L, 42L, "hello");
        TdApi.UpdateNewMessage update2 = makeUpdate(100L, 42L, "hello"); // same chatId + messageId

        StepVerifier.create(publisher.publishMessage(update1.message, update1, null, true))
                .verifyComplete();
        StepVerifier.create(publisher.publishMessage(update2.message, update2, null, true))
                .verifyComplete();

        // First message sends to both telegram.raw.messages and knowledge.raw.messages (2 sends).
        // Duplicate is suppressed by deduplication cache (0 additional sends).
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void publishMessage_differentMessages_sendsBoth() {
        TdApi.UpdateNewMessage update1 = makeUpdate(100L, 1L, "hello");
        TdApi.UpdateNewMessage update2 = makeUpdate(100L, 2L, "world"); // different messageId

        StepVerifier.create(publisher.publishMessage(update1.message, update1, null, true))
                .verifyComplete();
        StepVerifier.create(publisher.publishMessage(update2.message, update2, null, true))
                .verifyComplete();

        // Each distinct message sends to both telegram.raw.messages and knowledge.raw.messages.
        verify(kafkaTemplate, times(4)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishMessage_sendsToTelegramAndKnowledgeTopics() {
        TdApi.UpdateNewMessage update = makeUpdate(100L, 10L, "hello");

        StepVerifier.create(publisher.publishMessage(update.message, update, null, true))
                .verifyComplete();

        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(recordCaptor.capture());
        var records = recordCaptor.getAllValues();
        assertThat(records.stream().map(ProducerRecord::topic).toList())
                .containsExactlyInAnyOrder("telegram.raw.messages", "knowledge.raw.messages");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishMessage_knowledgeForkFalse_sendsOnlyToTelegramTopic() {
        TdApi.UpdateNewMessage update = makeUpdate(100L, 20L, "hello");

        StepVerifier.create(publisher.publishMessage(update.message, update, null, false))
                .verifyComplete();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(1)).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("telegram.raw.messages");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishMessage_knowledgeForkTrue_sendsToBothTopics() {
        TdApi.UpdateNewMessage update = makeUpdate(100L, 21L, "hello");

        StepVerifier.create(publisher.publishMessage(update.message, update, null, true))
                .verifyComplete();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(captor.capture());
        assertThat(captor.getAllValues().stream().map(ProducerRecord::topic).toList())
                .containsExactlyInAnyOrder("telegram.raw.messages", "knowledge.raw.messages");
    }

    @Test
    void extractMetadata_messageText_setsContentTypeText() {
        TdApi.UpdateNewMessage update = makeUpdate(100L, 3L, "hello");

        StepVerifier.create(publisher.publishMessage(update.message, update, null, true))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
        assertThat(captor.getValue().value()).contains("\"contentType\":\"text\"");
    }

    @Test
    void extractMetadata_messageSticker_setsContentTypeSticker() {
        TdApi.UpdateNewMessage update = makeStickerUpdate(100L, 4L);

        StepVerifier.create(publisher.publishMessage(update.message, update, null, true))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
        assertThat(captor.getValue().value()).contains("\"contentType\":\"sticker\"");
    }

    @Test
    void extractMetadata_messagePhoto_setsContentTypePhoto() {
        TdApi.UpdateNewMessage update = makePhotoUpdate(100L, 5L);

        StepVerifier.create(publisher.publishMessage(update.message, update, null, true))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
        assertThat(captor.getValue().value()).contains("\"contentType\":\"photo\"");
    }

    private TdApi.UpdateNewMessage makeStickerUpdate(long chatId, long messageId) {
        TdApi.MessageSticker content = new TdApi.MessageSticker();
        TdApi.Message message = new TdApi.Message();
        message.id = messageId;
        message.chatId = chatId;
        message.content = content;
        TdApi.UpdateNewMessage update = new TdApi.UpdateNewMessage();
        update.message = message;
        return update;
    }

    private TdApi.UpdateNewMessage makePhotoUpdate(long chatId, long messageId) {
        TdApi.MessagePhoto content = new TdApi.MessagePhoto();
        TdApi.Message message = new TdApi.Message();
        message.id = messageId;
        message.chatId = chatId;
        message.content = content;
        TdApi.UpdateNewMessage update = new TdApi.UpdateNewMessage();
        update.message = message;
        return update;
    }

    private TdApi.UpdateNewMessage makeUpdate(long chatId, long messageId, String text) {
        TdApi.FormattedText ft = new TdApi.FormattedText();
        ft.text = text;
        ft.entities = new TdApi.TextEntity[0];
        TdApi.MessageText content = new TdApi.MessageText();
        content.text = ft;
        TdApi.Message message = new TdApi.Message();
        message.id = messageId;
        message.chatId = chatId;
        message.content = content;
        TdApi.UpdateNewMessage update = new TdApi.UpdateNewMessage();
        update.message = message;
        return update;
    }
}
