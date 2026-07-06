package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.service.BackfillService;
import io.emcip.knowledge.engine.service.EmbeddingBackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Backfill", description = "Trigger and monitor chat history and embedding backfill")
@RestController
@RequestMapping("/api/knowledge/backfill")
@RequiredArgsConstructor
public class BackfillController {

    private final BackfillService backfillService;
    private final EmbeddingBackfillService embeddingBackfillService;

    @Operation(summary = "Trigger backfill for a Telegram chat")
    @PostMapping
    public ResponseEntity<Map<String, Object>> triggerBackfill(
            @RequestBody BackfillRequest request) {
        String backfillId =
                backfillService.triggerBackfill(
                        request.accountId(),
                        request.chatId(),
                        request.fromDate(),
                        request.tenantId());
        return ResponseEntity.accepted()
                .body(Map.of("backfillId", backfillId, "status", "RUNNING"));
    }

    @Operation(summary = "Get backfill progress")
    @GetMapping("/status")
    public BackfillService.BackfillStatus getStatus(@RequestParam String backfillId) {
        return backfillService.getStatus(backfillId);
    }

    @Operation(summary = "Preview how many documents have missing embeddings")
    @GetMapping("/embeddings/preview")
    public EmbeddingBackfillService.BackfillStatus previewEmbeddingBackfill() {
        return embeddingBackfillService.preview();
    }

    @Operation(summary = "Trigger embedding backfill for documents with missing embeddings")
    @PostMapping("/embeddings")
    public ResponseEntity<Map<String, Object>> triggerEmbeddingBackfill() {
        String backfillId = embeddingBackfillService.triggerBackfill();
        return ResponseEntity.accepted()
                .body(Map.of("backfillId", backfillId, "status", "RUNNING"));
    }

    @Operation(summary = "Get embedding backfill progress")
    @GetMapping("/embeddings/status")
    public EmbeddingBackfillService.BackfillStatus getEmbeddingBackfillStatus(
            @RequestParam String backfillId) {
        return embeddingBackfillService.getStatus(backfillId);
    }

    public record BackfillRequest(UUID accountId, long chatId, long fromDate, UUID tenantId) {}
}
