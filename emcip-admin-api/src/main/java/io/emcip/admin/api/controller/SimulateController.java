package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.admin.api.service.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/simulate")
@Tag(name = "Simulation", description = "Inject test messages through the full pipeline")
@RequiredArgsConstructor
public class SimulateController {

    private final SimulationService simulationService;

    @Operation(summary = "Simulate a Telegram message through the processing pipeline")
    @PostMapping("/message")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> simulateMessage(@RequestBody SimulateMessageRequest req) {
        return simulationService
                .simulate(req)
                .map(
                        result ->
                                Map.of(
                                        "eventId", result.eventId(),
                                        "topic", result.topic(),
                                        "chatId", req.getChatId(),
                                        "status", "published"));
    }
}
