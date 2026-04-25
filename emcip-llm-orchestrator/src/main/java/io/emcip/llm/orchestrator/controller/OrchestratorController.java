package io.emcip.llm.orchestrator.controller;

import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** REST API for model configuration, prompt templates, and cost analytics. */
@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class OrchestratorController {

    private final LlmOrchestratorService orchestratorService;
    private final CostTrackingService costTrackingService;
    private final ModelConfigRepository modelConfigRepository;
    private final PromptTemplateRepository promptTemplateRepository;

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

    @PutMapping("/models/{id}")
    public ResponseEntity<ModelConfig> updateModel(
            @PathVariable UUID id, @RequestBody ModelConfig update) {
        ModelConfig existing =
                modelConfigRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Model not found: " + id));
        existing.setModelKey(update.getModelKey());
        existing.setProvider(update.getProvider());
        existing.setModelName(update.getModelName());
        existing.setDescription(update.getDescription());
        existing.setTaskType(update.getTaskType());
        existing.setInputCostPer1kTokens(update.getInputCostPer1kTokens());
        existing.setOutputCostPer1kTokens(update.getOutputCostPer1kTokens());
        existing.setContextWindow(update.getContextWindow());
        existing.setMaxOutputTokens(update.getMaxOutputTokens());
        existing.setAvgLatencyMs(update.getAvgLatencyMs());
        existing.setSupportsStreaming(update.getSupportsStreaming());
        existing.setActive(update.getActive());
        existing.setPriority(update.getPriority());
        return ResponseEntity.ok(modelConfigRepository.save(existing));
    }

    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModel(@PathVariable UUID id) {
        if (!modelConfigRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found: " + id);
        }
        modelConfigRepository.deleteById(id);
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

    @PutMapping("/templates/{id}")
    public ResponseEntity<PromptTemplate> updateTemplate(
            @PathVariable UUID id, @RequestBody PromptTemplate update) {
        PromptTemplate existing =
                promptTemplateRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Template not found: " + id));
        existing.setName(update.getName());
        existing.setVersion(update.getVersion());
        existing.setDescription(update.getDescription());
        existing.setModelProvider(update.getModelProvider());
        existing.setModelName(update.getModelName());
        existing.setSystemPrompt(update.getSystemPrompt());
        existing.setUserPromptTemplate(update.getUserPromptTemplate());
        existing.setTemperature(update.getTemperature());
        existing.setMaxTokens(update.getMaxTokens());
        existing.setActive(update.getActive());
        existing.setPriority(update.getPriority());
        return ResponseEntity.ok(promptTemplateRepository.save(existing));
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable UUID id) {
        if (!promptTemplateRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + id);
        }
        promptTemplateRepository.deleteById(id);
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
