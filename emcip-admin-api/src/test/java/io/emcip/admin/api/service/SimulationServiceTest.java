package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.dto.SimulateMessageRequest;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private SimulationService simulationService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        simulationService =
                new SimulationService(kafkaTemplate, new tools.jackson.databind.ObjectMapper());
        SendResult<String, String> sendResult =
                new SendResult<>(null, new RecordMetadata(null, 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    private SimulateMessageRequest request(long chatId) {
        SimulateMessageRequest req = new SimulateMessageRequest();
        req.setChatId(chatId);
        req.setText("hello world");
        return req;
    }

    @Test
    void simulate_publishesToKafka() {
        simulationService.simulate(request(99L)).block();

        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @Test
    void simulate_returnsEventIdAndTopic() {
        StepVerifier.create(simulationService.simulate(request(12345L)))
                .assertNext(
                        result -> {
                            assertThat(result.eventId()).isNotNull();
                            assertThat(result.topic()).isEqualTo(SimulationService.TOPIC);
                        })
                .verifyComplete();
    }

    @Test
    void simulate_usesDefaultsForNullFields() {
        SimulateMessageRequest req = new SimulateMessageRequest();
        req.setChatId(77L);

        StepVerifier.create(simulationService.simulate(req))
                .assertNext(result -> assertThat(result.topic()).isEqualTo(SimulationService.TOPIC))
                .verifyComplete();
    }
}
