package io.emcip.intent.classifier.controller;

import io.emcip.intent.classifier.dto.IntentSignalConfigDto;
import io.emcip.intent.classifier.entity.IntentSignalConfig;
import io.emcip.intent.classifier.repository.IntentSignalConfigRepository;
import io.emcip.intent.classifier.service.IntentClassificationService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/intent-signal-config")
@RequiredArgsConstructor
@Slf4j
public class IntentSignalConfigController {

    private final IntentSignalConfigRepository repository;
    private final IntentClassificationService classificationService;

    @GetMapping
    public ResponseEntity<IntentSignalConfigDto> get(
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
        var config =
                repository.findByTenantId(tenantId).or(() -> repository.findByTenantIdIsNull());
        return config.map(c -> ResponseEntity.ok(toDto(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping
    public IntentSignalConfigDto upsert(
            @RequestBody IntentSignalConfigDto dto,
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
        var now = Instant.now();
        var config =
                repository
                        .findByTenantId(tenantId)
                        .orElseGet(
                                () -> {
                                    var c = new IntentSignalConfig();
                                    c.setTenantId(tenantId);
                                    c.setCreatedAt(now);
                                    return c;
                                });
        config.setDescription(dto.description());
        config.setForeignScriptRatio(dto.foreignScriptRatio());
        config.setCyrillicRatio(dto.cyrillicRatio());
        config.setLookalikeSuspicion(dto.lookalikeSuspicion());
        config.setZeroWidthAbuse(dto.zeroWidthAbuse());
        config.setCapsRatio(dto.capsRatio());
        config.setToxicityWords(dto.toxicityWords());
        config.setUpdatedAt(now);
        var saved = repository.save(config);
        classificationService.refreshSignalConfig();
        return toDto(saved);
    }

    private IntentSignalConfigDto toDto(IntentSignalConfig c) {
        return new IntentSignalConfigDto(
                c.getId(),
                c.getTenantId(),
                c.getDescription(),
                c.getForeignScriptRatio(),
                c.getCyrillicRatio(),
                c.getLookalikeSuspicion(),
                c.getZeroWidthAbuse(),
                c.getCapsRatio(),
                c.getToxicityWords(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
