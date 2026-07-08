package io.emcip.llm.orchestrator.controller;

import io.emcip.llm.orchestrator.client.LlmResponse;
import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
@Tag(
        name = "LLM Orchestrator",
        description = "Manage AI models, prompt templates, and query cost summaries")
@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class OrchestratorController {

    private final LlmOrchestratorService orchestratorService;
    private final CostTrackingService costTrackingService;
    private final ModelConfigRepository modelConfigRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final LlmProviderConfigService providerConfigService;
    private final LlmProviderConfigRepository providerConfigRepository;
    private final OpenAiCompatibleLlmClient llmClient;

    public record AnalyseRequest(String prompt, String taskType) {}

    public record AnalyseResponse(boolean success, String analysis, String model) {}

    public record EmbedRequest(String input) {}

    public record EmbedResponse(boolean success, float[] embedding, String model) {}

    public record ChatMessage(String role, String content) {}

    public record ChatRequest(List<ChatMessage> messages, String taskType) {}

    public record ChatResponse(boolean success, String content, String model) {}

    public record WarmUpRequest(List<String> taskTypes) {}

    public record WarmUpResult(boolean ready, String model, long latencyMs, String error) {}

    public record WarmUpResponse(Map<String, WarmUpResult> results) {}

    // --- Models ---

    @Operation(summary = "List all model configurations")
    @GetMapping("/models")
    public List<ModelConfig> listModels() {
        return modelConfigRepository.findByActiveTrueOrderByPriorityAsc();
    }

    @Operation(summary = "Create a new model configuration")
    @PostMapping("/models")
    public ResponseEntity<ModelConfig> createModel(@RequestBody ModelConfig modelConfig) {
        ModelConfig saved = orchestratorService.saveModelConfig(modelConfig);
        log.info("Created model config: {}", saved.getModelKey());
        return ResponseEntity.status(201).body(saved);
    }

    @Operation(summary = "Update an existing model configuration")
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

    @Operation(summary = "Delete a model configuration")
    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModel(@PathVariable UUID id) {
        if (!modelConfigRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found: " + id);
        }
        modelConfigRepository.deleteById(id);
    }

    // --- Templates ---

    @Operation(summary = "List all prompt templates")
    @GetMapping("/templates")
    public List<PromptTemplate> listTemplates() {
        return orchestratorService.getAllActivePromptTemplates();
    }

    @Operation(summary = "Get a prompt template by name")
    @GetMapping("/templates/{name}")
    public ResponseEntity<PromptTemplate> getTemplateByName(@PathVariable String name) {
        return orchestratorService
                .getPromptTemplate(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new prompt template")
    @PostMapping("/templates")
    public ResponseEntity<PromptTemplate> createTemplate(@RequestBody PromptTemplate template) {
        resolveModelConfig(template);
        PromptTemplate saved = orchestratorService.savePromptTemplate(template);
        log.info("Created prompt template: {}", saved.getName());
        return ResponseEntity.status(201).body(saved);
    }

    @Operation(summary = "Update an existing prompt template")
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
        if (Boolean.TRUE.equals(existing.getSystem())
                && !existing.getName().equals(update.getName())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cannot rename system template: " + existing.getName());
        }
        existing.setName(update.getName());
        existing.setVersion(update.getVersion());
        existing.setDescription(update.getDescription());
        resolveModelConfig(update);
        existing.setModelConfig(update.getModelConfig());
        existing.setSystemPrompt(update.getSystemPrompt());
        existing.setUserPromptTemplate(update.getUserPromptTemplate());
        existing.setTemperature(update.getTemperature());
        existing.setMaxTokens(update.getMaxTokens());
        existing.setActive(update.getActive());
        existing.setPriority(update.getPriority());
        return ResponseEntity.ok(promptTemplateRepository.save(existing));
    }

    @Operation(summary = "Delete a prompt template")
    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable UUID id) {
        PromptTemplate template =
                promptTemplateRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Template not found: " + id));
        if (Boolean.TRUE.equals(template.getSystem())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cannot delete system template: " + template.getName());
        }
        promptTemplateRepository.deleteById(id);
    }

    /**
     * Resolve a ModelConfig stub (Jackson-deserialized with only id) into a managed entity. Without
     * this, Hibernate throws TransientPropertyValueException when saving because the stub is not
     * managed in the persistence context.
     */
    private void resolveModelConfig(PromptTemplate template) {
        if (template.getModelConfig() != null && template.getModelConfig().getId() != null) {
            ModelConfig managed =
                    modelConfigRepository
                            .findById(template.getModelConfig().getId())
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.BAD_REQUEST,
                                                    "Model config not found: "
                                                            + template.getModelConfig().getId()));
            template.setModelConfig(managed);
        } else {
            template.setModelConfig(null);
        }
    }

    // --- Costs ---

    @Operation(summary = "Get LLM cost summary for a time range")
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

    @Operation(summary = "Get aggregated LLM cost totals for a time range")
    @GetMapping("/costs/totals")
    public Map<String, Object> costTotals(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Map<String, Object> totals = costTrackingService.getTotals(from, to);
        totals.put("from", from.toString());
        totals.put("to", to.toString());
        return totals;
    }

    @Operation(summary = "Get LLM costs aggregated by model for a time range")
    @GetMapping("/costs/by-model")
    public List<Map<String, Object>> costByModel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return costTrackingService.getByModel(from, to);
    }

    @Operation(summary = "Get LLM costs aggregated by day for a time range")
    @GetMapping("/costs/by-day")
    public List<Map<String, Object>> costByDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return costTrackingService.getByDay(from, to);
    }

    // --- Provider Config ---

    private Map<String, Object> maskConfig(LlmProviderConfig p) {
        return Map.of(
                "id", p.getId().toString(),
                "name", p.getName(),
                "baseUrl", p.getBaseUrl(),
                "apiKey", p.getApiKey() != null && !p.getApiKey().isBlank() ? "***" : "",
                "active", p.getActive());
    }

    @Operation(summary = "List all LLM provider configurations (api_key masked)")
    @GetMapping("/provider-config")
    public List<Map<String, Object>> listProviderConfigs() {
        return providerConfigRepository.findAll().stream().map(this::maskConfig).toList();
    }

    @Operation(summary = "Create a new LLM provider configuration")
    @PostMapping("/provider-config")
    public ResponseEntity<Map<String, Object>> createProviderConfig(
            @RequestBody LlmProviderConfig config) {
        LlmProviderConfig saved = providerConfigService.saveProvider(config);
        log.info("Created provider config: name={}, active={}", saved.getName(), saved.getActive());
        return ResponseEntity.status(201).body(maskConfig(saved));
    }

    @Operation(summary = "Update an existing LLM provider configuration")
    @PutMapping("/provider-config/{id}")
    public ResponseEntity<Map<String, Object>> updateProviderConfig(
            @PathVariable UUID id, @RequestBody LlmProviderConfig update) {
        LlmProviderConfig existing =
                providerConfigRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Provider config not found: " + id));
        existing.setName(update.getName());
        existing.setBaseUrl(update.getBaseUrl());
        if (update.getApiKey() != null
                && !update.getApiKey().isBlank()
                && !"***".equals(update.getApiKey())) {
            existing.setApiKey(update.getApiKey());
        }
        existing.setActive(update.getActive());
        LlmProviderConfig saved = providerConfigService.saveProvider(existing);
        log.info("Updated provider config: id={}, active={}", id, saved.getActive());
        return ResponseEntity.ok(maskConfig(saved));
    }

    @Operation(summary = "Delete a LLM provider configuration")
    @DeleteMapping("/provider-config/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProviderConfig(@PathVariable UUID id) {
        if (!providerConfigRepository.existsById(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Provider config not found: " + id);
        }
        providerConfigRepository.deleteById(id);
    }

    @Operation(
            summary =
                    "List models on the active provider proxy, or on an ad-hoc URL (for testing"
                            + " before save)")
    @GetMapping("/provider-config/models")
    public ResponseEntity<Map<String, Object>> listProxyModels(
            @RequestParam(required = false) String baseUrl,
            @RequestParam(required = false) String apiKey) {
        String effectiveBaseUrl;
        String effectiveApiKey;
        if (baseUrl != null && !baseUrl.isBlank()) {
            effectiveBaseUrl = baseUrl;
            effectiveApiKey = apiKey;
        } else {
            Optional<LlmProviderConfig> active = providerConfigService.getActiveProvider();
            if (active.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            effectiveBaseUrl = active.get().getBaseUrl();
            effectiveApiKey = active.get().getApiKey();
        }
        List<String> models =
                providerConfigService.fetchAvailableModels(effectiveBaseUrl, effectiveApiKey);
        return ResponseEntity.ok(
                Map.<String, Object>of(
                        "baseUrl",
                        effectiveBaseUrl,
                        "models",
                        models,
                        "reachable",
                        !models.isEmpty()));
    }

    @Operation(summary = "Run an ad-hoc LLM analysis using the flag_analyse template")
    @PostMapping("/analyse")
    public ResponseEntity<AnalyseResponse> analyse(@RequestBody AnalyseRequest req) {
        // Look up the flag_analyse template first
        Optional<PromptTemplate> templateOpt =
                orchestratorService.getPromptTemplate("flag_analyse");

        String systemPrompt;
        int maxTokens;
        Double temperature;
        String modelName;

        if (templateOpt.isPresent()) {
            PromptTemplate template = templateOpt.get();
            systemPrompt = template.getSystemPrompt();
            maxTokens = template.getMaxTokens();
            temperature = template.getTemperature();

            if (template.getModelConfig() != null) {
                modelName = template.getModelConfig().getModelName();
            } else {
                String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
                Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
                if (modelOpt.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(
                                    new AnalyseResponse(
                                            false,
                                            "No model configured for task: " + taskType,
                                            null));
                }
                modelName = modelOpt.get().getModelName();
            }
        } else {
            // Fallback to hardcoded defaults
            String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
            Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
            if (modelOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(
                                new AnalyseResponse(
                                        false, "No model configured for task: " + taskType, null));
            }
            modelName = modelOpt.get().getModelName();
            systemPrompt =
                    "You are a moderation analyst for the EMCIP platform. Analyse the"
                            + " provided flag data and explain the moderation decision clearly"
                            + " and concisely.";
            maxTokens = 8192;
            temperature = null;
        }

        try {
            LlmResponse response =
                    llmClient.call(modelName, systemPrompt, req.prompt(), maxTokens, temperature);
            return ResponseEntity.ok(
                    new AnalyseResponse(true, response.content(), response.model()));
        } catch (Exception e) {
            log.error("Ad-hoc LLM analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AnalyseResponse(false, "LLM call failed: " + e.getMessage(), null));
        }
    }

    @Operation(
            summary = "Generate an embedding vector for the given text using the EMBED task model")
    @PostMapping("/embed")
    public ResponseEntity<EmbedResponse> embed(@RequestBody EmbedRequest req) {
        Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask("EMBED");
        if (modelOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new EmbedResponse(false, new float[0], null));
        }
        ModelConfig model = modelOpt.get();
        try {
            float[] embedding = llmClient.embed(model.getModelName(), req.input());
            return ResponseEntity.ok(new EmbedResponse(true, embedding, model.getModelName()));
        } catch (Exception e) {
            log.error("Embedding call failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new EmbedResponse(false, new float[0], null));
        }
    }

    @Operation(summary = "Multi-turn chat using the flag_analysis template")
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        Optional<PromptTemplate> templateOpt =
                orchestratorService.getPromptTemplate("flag_analysis");

        int maxTokens;
        Double temperature;
        String modelName;

        if (templateOpt.isPresent()) {
            PromptTemplate template = templateOpt.get();
            maxTokens = template.getMaxTokens();
            temperature = template.getTemperature();

            if (template.getModelConfig() != null) {
                modelName = template.getModelConfig().getModelName();
            } else {
                String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
                Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
                if (modelOpt.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(
                                    new ChatResponse(
                                            false,
                                            "No model configured for task: " + taskType,
                                            null));
                }
                modelName = modelOpt.get().getModelName();
            }
        } else {
            String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
            Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
            if (modelOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(
                                new ChatResponse(
                                        false, "No model configured for task: " + taskType, null));
            }
            modelName = modelOpt.get().getModelName();
            maxTokens = 8192;
            temperature = null;
        }

        try {
            List<Map<String, String>> messages =
                    req.messages().stream()
                            .map(m -> Map.of("role", m.role(), "content", m.content()))
                            .toList();
            LlmResponse response = llmClient.chat(modelName, messages, maxTokens, temperature);
            return ResponseEntity.ok(new ChatResponse(true, response.content(), response.model()));
        } catch (Exception e) {
            log.error("Chat call failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatResponse(false, "LLM call failed: " + e.getMessage(), null));
        }
    }

    @Operation(summary = "Warm up LLM models by sending a minimal inference request")
    @PostMapping("/warm-up")
    public ResponseEntity<WarmUpResponse> warmUp(@RequestBody WarmUpRequest req) {
        Map<String, WarmUpResult> results = new LinkedHashMap<>();
        for (String taskType : req.taskTypes()) {
            Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
            if (modelOpt.isEmpty()) {
                results.put(
                        taskType,
                        new WarmUpResult(
                                false, null, 0, "No model configured for task type: " + taskType));
                continue;
            }
            ModelConfig model = modelOpt.get();
            long start = System.nanoTime();
            try {
                if ("EMBED".equals(taskType)) {
                    llmClient.embed(model.getModelName(), "ping");
                } else {
                    llmClient.chat(
                            model.getModelName(),
                            List.of(Map.of("role", "user", "content", "ping")),
                            16,
                            null);
                }
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                results.put(
                        taskType, new WarmUpResult(true, model.getModelName(), latencyMs, null));
            } catch (Exception e) {
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                log.warn("Warm-up failed for {}: {}", taskType, e.getMessage());
                results.put(
                        taskType,
                        new WarmUpResult(false, model.getModelName(), latencyMs, e.getMessage()));
            }
        }
        return ResponseEntity.ok(new WarmUpResponse(results));
    }
}
