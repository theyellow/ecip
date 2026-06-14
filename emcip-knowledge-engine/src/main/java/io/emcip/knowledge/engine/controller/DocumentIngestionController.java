package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Document Ingestion", description = "Ingest documents into the knowledge base")
@RestController
@RequestMapping("/api/knowledge/ingest")
@RequiredArgsConstructor
public class DocumentIngestionController {

    private final DocumentIngestionService ingestionService;

    @Operation(summary = "Ingest a URL into the knowledge base")
    @PostMapping("/url")
    public ResponseEntity<Map<String, Object>> ingestUrl(@RequestBody UrlRequest request) {
        List<UUID> ids = ingestionService.ingestUrl(request.url(), request.tenantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("url", request.url(), "chunks", ids.size(), "documentIds", ids));
    }

    @Operation(summary = "Ingest uploaded text content")
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> ingestUpload(@RequestBody UploadRequest request) {
        List<UUID> ids =
                ingestionService.ingestText(
                        request.content(), request.sourceName(), request.tenantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        Map.of(
                                "sourceName", request.sourceName(),
                                "chunks", ids.size(),
                                "documentIds", ids));
    }

    public record UrlRequest(String url, UUID tenantId) {}

    public record UploadRequest(String content, String sourceName, UUID tenantId) {}
}
