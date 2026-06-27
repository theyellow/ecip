package io.emcip.intent.classifier.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.intent.classifier.entity.IntentRule;
import io.emcip.intent.classifier.entity.IntentSignalConfig;
import io.emcip.intent.classifier.repository.IntentRuleRepository;
import io.emcip.intent.classifier.repository.IntentSignalConfigRepository;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Rule-based intent classification service. Loads rules and signal config from the database, caches
 * them in memory, and refreshes on write.
 */
@Service
public class IntentClassificationService {

    private static final Logger log = LoggerFactory.getLogger(IntentClassificationService.class);
    private static final String TOPIC_OUTPUT = "messages.classified";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SignalDetector signalDetector;
    private final IntentRuleRepository ruleRepository;
    private final IntentSignalConfigRepository signalConfigRepository;

    // In-memory caches — rebuilt on every refresh
    private volatile List<IntentRule> globalRules = List.of();
    private volatile Map<UUID, List<IntentRule>> tenantRules = Map.of();
    private volatile Map<String, Pattern> compiledPatterns = Map.of();
    private volatile IntentSignalConfig globalSignalConfig = null;
    private volatile Map<UUID, IntentSignalConfig> tenantSignalConfigs = Map.of();
    private volatile List<Pattern> compiledToxicityPatterns = List.of();

    public IntentClassificationService(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            SignalDetector signalDetector,
            IntentRuleRepository ruleRepository,
            IntentSignalConfigRepository signalConfigRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.signalDetector = signalDetector;
        this.ruleRepository = ruleRepository;
        this.signalConfigRepository = signalConfigRepository;
    }

    @PostConstruct
    public void init() {
        refreshRules();
        refreshSignalConfig();
    }

    /** Reload classification rules from the database and rebuild in-memory caches. */
    public synchronized void refreshRules() {
        var newPatterns = new ConcurrentHashMap<String, Pattern>();
        var newGlobal = ruleRepository.findByTenantIdIsNullAndActiveTrueOrderByPriorityAsc();
        for (var rule : newGlobal) {
            if ("REGEX".equals(rule.getMatchMode())) {
                newPatterns.put(
                        rule.getId(), Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE));
            }
        }
        // Load all tenant-specific active rules
        var allTenantRules =
                ruleRepository.findAll().stream()
                        .filter(r -> r.getTenantId() != null && Boolean.TRUE.equals(r.getActive()))
                        .toList();
        var newTenantMap = new ConcurrentHashMap<UUID, List<IntentRule>>();
        for (var rule : allTenantRules) {
            newTenantMap.computeIfAbsent(rule.getTenantId(), k -> new ArrayList<>()).add(rule);
            if ("REGEX".equals(rule.getMatchMode())) {
                newPatterns.put(
                        rule.getId(), Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE));
            }
        }
        // Sort each tenant list by priority
        newTenantMap.replaceAll(
                (k, v) ->
                        v.stream()
                                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                                .toList());

        this.globalRules = newGlobal;
        this.tenantRules = newTenantMap;
        this.compiledPatterns = newPatterns;
        log.info(
                "Refreshed intent rules: {} global, {} tenant-specific",
                newGlobal.size(),
                allTenantRules.size());
    }

    /** Reload signal config thresholds from the database and recompile toxicity patterns. */
    public synchronized void refreshSignalConfig() {
        this.globalSignalConfig = signalConfigRepository.findByTenantIdIsNull().orElse(null);
        var allTenantConfigs =
                signalConfigRepository.findAll().stream()
                        .filter(c -> c.getTenantId() != null)
                        .toList();
        var newMap = new ConcurrentHashMap<UUID, IntentSignalConfig>();
        for (var c : allTenantConfigs) {
            newMap.put(c.getTenantId(), c);
        }
        this.tenantSignalConfigs = newMap;

        // Compile toxicity patterns from global config
        this.compiledToxicityPatterns =
                Optional.ofNullable(globalSignalConfig)
                        .map(c -> SignalDetector.buildToxicityPatterns(c.getToxicityWords()))
                        .orElse(List.of());
        log.info(
                "Refreshed signal config: global={}, tenants={}",
                globalSignalConfig != null,
                newMap.size());
    }

    /** Classify a Telegram message and publish the result. */
    public EventSchemas.IntentClassifiedEvent classify(
            EventSchemas.TelegramMessageEvent message, String tenantIdStr) {
        String text = message.text() != null ? message.text() : "";
        UUID tenantId = tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;

        // Build ordered rule list: tenant-specific first, then global
        List<IntentRule> rules = new ArrayList<>();
        if (tenantId != null) {
            rules.addAll(tenantRules.getOrDefault(tenantId, List.of()));
        }
        rules.addAll(globalRules);

        String matchedIntent = null;
        double highestConfidence = 0.0;
        List<String> matchedRules = new ArrayList<>();

        // Apply rules in priority order
        for (IntentRule rule : rules) {
            if (matches(rule, text)) {
                matchedRules.add(rule.getName());
                if (rule.getConfidence() > highestConfidence) {
                    highestConfidence = rule.getConfidence();
                    matchedIntent = rule.getIntent();
                }
            }
        }

        // Resolve signal config for this tenant (fall back to global)
        IntentSignalConfig signalCfg =
                (tenantId != null ? tenantSignalConfigs.get(tenantId) : null);
        if (signalCfg == null) signalCfg = globalSignalConfig;

        // Use tenant toxicity patterns if tenant has its own config, else use pre-compiled global
        List<Pattern> toxicityPatterns =
                signalCfg != null && tenantId != null && tenantSignalConfigs.containsKey(tenantId)
                        ? SignalDetector.buildToxicityPatterns(signalCfg.getToxicityWords())
                        : compiledToxicityPatterns;

        // Detect structural/script signals
        Map<String, Object> signals =
                signalDetector.detect(text, message.metadata(), toxicityPatterns);

        // Apply signal priority chain when no rule matched
        if (matchedIntent == null) {
            matchedIntent = resolveSignalIntent(signals, signalCfg);
        }

        // Build classification parameters
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textLength", text.length());
        params.put("chatId", message.chatId());
        params.put("senderId", message.senderId() != null ? message.senderId() : "");
        params.put("messageText", text);
        if (message.telegramMessageId() != null) {
            params.put("telegramMessageId", message.telegramMessageId());
        }
        params.putAll(signals);

        var classification =
                new EventSchemas.IntentClassifiedEvent(
                        UUID.randomUUID().toString(),
                        Instant.now().toString(),
                        EventSchemas.INTENT_CLASSIFIED_V1,
                        "IntentClassified",
                        message.eventId(),
                        matchedIntent,
                        highestConfidence,
                        params,
                        matchedRules);

        // Publish to Kafka, forwarding tenant_id header if present
        String json;
        try {
            json = objectMapper.writeValueAsString(classification);
        } catch (Exception e) {
            log.error("Failed to serialize classification event", e);
            throw new RuntimeException(e);
        }
        ProducerRecord<String, String> producerRecord =
                new ProducerRecord<>(TOPIC_OUTPUT, null, message.eventId(), json);
        if (tenantIdStr != null) {
            producerRecord.headers().add("tenant_id", tenantIdStr.getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(producerRecord);

        log.debug("Published classification for message {}: {}", message.eventId(), matchedIntent);
        return classification;
    }

    private boolean matches(IntentRule rule, String text) {
        String lower = text.toLowerCase();
        return switch (rule.getMatchMode()) {
            case "KEYWORD" ->
                    Arrays.stream(rule.getPattern().split("\\|"))
                            .anyMatch(kw -> lower.contains(kw.trim().toLowerCase()));
            case "REGEX" -> {
                Pattern p = compiledPatterns.get(rule.getId());
                yield p != null && p.matcher(text).find();
            }
            default -> false;
        };
    }

    private String resolveSignalIntent(Map<String, Object> signals, IntentSignalConfig cfg) {
        double foreignThreshold = cfg != null ? cfg.getForeignScriptRatio() : 0.6;
        double capsThreshold = cfg != null ? cfg.getCapsRatio() : 0.7;
        int lookalikThreshold = cfg != null ? cfg.getLookalikeSuspicion() : 3;
        int zeroWidthThreshold = cfg != null ? cfg.getZeroWidthAbuse() : 2;

        if (Boolean.TRUE.equals(signals.get("stickerOnly"))) return "FORMAT_STICKER_ONLY";
        if (Boolean.TRUE.equals(signals.get("imageOnly"))) return "FORMAT_IMAGE_ONLY";
        if (Boolean.TRUE.equals(signals.get("emojiOnly"))) return "FORMAT_EMOJI_ONLY";
        if (signals.get("lookalikeSuspicion") instanceof Integer count
                && count >= lookalikThreshold) return "LOOKALIKE_ABUSE";
        if (signals.get("zeroWidthAbuse") instanceof Integer count && count >= zeroWidthThreshold)
            return "FORMAT_ABUSE";
        if (signals.get("foreignScriptRatio") instanceof Double d && d >= foreignThreshold)
            return "SCRIPT_FOREIGN";
        if (signals.get("capsRatio") instanceof Double d && d >= capsThreshold) return "CAPS_HEAVY";
        if (signals.get("toxicityHint") instanceof Double d && d > 0.0) return "TOXICITY_HINT";
        return "UNKNOWN";
    }
}
