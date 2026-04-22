package io.emcip.llm.orchestrator.controller;

import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API for model configuration, prompt templates, and cost analytics. */
@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class OrchestratorController {

    private final LlmOrchestratorService orchestratorService;
    private final CostTrackingService costTrackingService;
    private final ModelConfigRepository modelConfigRepository;

    // --- Models ---

    @GetMapping("/models")
    public List<ModelConfig> listModels() {
        return modelConfigRepository.findByActiveTrueOrderByPriorityAsc();
    }

    @PostMapping("/models")
    public ResponseEntity<ModelConfig> createModel(@RequestBody ModelConfig modelConfig) {
        ModelConfig saved = orchestratorService.saveModelConfig(modelConfig);
        log.info("Created model config: {}", saved.getModelKey());
        return ResponseEntity.status(201).body(saved);
    }

    // --- Templates ---

    @GetMapping("/templates")
    public List<PromptTemplate> listTemplates() {
        return orchestratorService.getAllActivePromptTemplates();
    }

    @PostMapping("/templates")
    public ResponseEntity<PromptTemplate> createTemplate(@RequestBody PromptTemplate template) {
        PromptTemplate saved = orchestratorService.savePromptTemplate(template);
        log.info("Created prompt template: {}", saved.getName());
        return ResponseEntity.status(201).body(saved);
    }

    // --- Costs ---

    @GetMapping("/costs/summary")
    public Map<String, Object> costSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        double totalCost = costTrackingService.getTotalCostForPeriod(from, to);
        return Map.of(
                "from", from.toString(),
                "to", to.toString(),
                "totalCostUsd", totalCost);
    }
}
