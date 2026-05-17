package io.emcip.tdlib.adapter.service;

import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramEventPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;
    TelegramEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        publisher = new TelegramEventPublisher(kafkaTemplate);
        SendResult<String, String> sendResult =
                new SendResult<>(null, new RecordMetadata(null, 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    @Test
    void publishMessage_sameMessageTwice_onlySendsOnce() {
        TdApi.UpdateNewMessage update1 = makeUpdate(100L, 42L, "hello");
        TdApi.UpdateNewMessage update2 = makeUpdate(100L, 42L, "hello"); // same chatId + messageId

        StepVerifier.create(publisher.publishMessage(update1.message, update1)).verifyComplete();
        StepVerifier.create(publisher.publishMessage(update2.message, update2)).verifyComplete();

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void publishMessage_differentMessages_sendsBoth() {
        TdApi.UpdateNewMessage update1 = makeUpdate(100L, 1L, "hello");
        TdApi.UpdateNewMessage update2 = makeUpdate(100L, 2L, "world"); // different messageId

        StepVerifier.create(publisher.publishMessage(update1.message, update1)).verifyComplete();
        StepVerifier.create(publisher.publishMessage(update2.message, update2)).verifyComplete();

        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
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
