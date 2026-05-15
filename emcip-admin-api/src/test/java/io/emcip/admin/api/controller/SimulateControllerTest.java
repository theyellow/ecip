package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SimulateControllerTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private SimulateController controller;
    private WebTestClient webTestClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new SimulateController(kafkaTemplate);
        webTestClient = WebTestClient.bindToController(controller).build();
        SendResult<String, String> sendResult =
                new SendResult<>(null, new RecordMetadata(null, 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    private SimulateController.SimulateMessageRequest request(long chatId) {
        SimulateController.SimulateMessageRequest req =
                new SimulateController.SimulateMessageRequest();
        req.setChatId(chatId);
        req.setText("hello world");
        return req;
    }

    @Test
    void simulateMessage_returnsPublishedResponse() {
        StepVerifier.create(controller.simulateMessage(request(12345L)))
                .assertNext(
                        response -> {
                            assertThat(response.get("topic")).isEqualTo("telegram.raw.messages");
                            assertThat(response.get("chatId")).isEqualTo(12345L);
                            assertThat(response.get("status")).isEqualTo("published");
                            assertThat(response.get("eventId")).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void simulateMessage_publishesToKafka() {
        controller.simulateMessage(request(99L)).block();

        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @Test
    void simulateMessage_usesDefaultsForNullFields() {
        SimulateController.SimulateMessageRequest req =
                new SimulateController.SimulateMessageRequest();
        req.setChatId(77L);

        StepVerifier.create(controller.simulateMessage(req))
                .assertNext(response -> assertThat(response.get("status")).isEqualTo("published"))
                .verifyComplete();
    }

    @Test
    void simulateMessage_returns202() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .bodyValue(request(555L))
                .exchange()
                .expectStatus()
                .isAccepted();
    }

    @Test
    void simulateMessage_returnsCorrectResponseBody() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .bodyValue(request(666L))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.topic")
                .isEqualTo("telegram.raw.messages")
                .jsonPath("$.chatId")
                .isEqualTo(666L)
                .jsonPath("$.status")
                .isEqualTo("published")
                .jsonPath("$.eventId")
                .isNotEmpty();
    }
}
