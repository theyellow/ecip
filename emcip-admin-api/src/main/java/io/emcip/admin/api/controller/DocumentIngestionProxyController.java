package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Proxies document ingestion requests to the knowledge-engine service. Admin-UI → admin-api →
 * knowledge-engine (API Gateway pattern). Mirrors BackfillProxyController.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge/ingest")
@Tag(name = "Document Ingestion", description = "Submit and monitor document ingestion jobs")
public class DocumentIngestionProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;

    public DocumentIngestionProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry,
            ObjectMapper objectMapper) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge-ingest");
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Submit a URL for ingestion")
    @PostMapping("/url")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> ingestUrl(@RequestBody String body) {
        return Mono.deferContextual(
                        ctx -> {
                            String forwarded;
                            try {
                                forwarded =
                                        KnowledgeTenantResolver.rewriteBody(
                                                objectMapper, body, ctx);
                            } catch (IllegalArgumentException e) {
                                return Mono.just(ResponseEntity.badRequest().<String>build());
                            }
                            return knowledgeWebClient
                                    .post()
                                    .uri("/api/knowledge/ingest/url")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(forwarded)
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .map(
                                            responseBody ->
                                                    ResponseEntity.accepted()
                                                            .<String>body(responseBody))
                                    .onErrorResume(
                                            WebClientResponseException.class,
                                            e -> conflictOrUnavailable(e, "Ingest URL"));
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Upload a document for ingestion")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> ingestUpload(
            @RequestPart("file") FilePart file, @RequestParam(required = false) UUID tenantId) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenant;
                            try {
                                tenant =
                                        KnowledgeTenantResolver.resolve(
                                                ctx, tenantId == null ? null : tenantId.toString());
                            } catch (IllegalArgumentException e) {
                                return Mono.just(ResponseEntity.badRequest().<String>build());
                            }
                            return DataBufferUtils.join(file.content())
                                    .flatMap(
                                            dataBuffer -> {
                                                byte[] bytes =
                                                        new byte[dataBuffer.readableByteCount()];
                                                dataBuffer.read(bytes);
                                                DataBufferUtils.release(dataBuffer);

                                                String filename = file.filename();
                                                MultiValueMap<String, Object> parts =
                                                        new LinkedMultiValueMap<>();
                                                parts.add(
                                                        "file",
                                                        new ByteArrayResource(bytes) {
                                                            @Override
                                                            public String getFilename() {
                                                                return filename;
                                                            }
                                                        });
                                                if (tenant != null) {
                                                    parts.add("tenantId", tenant);
                                                }
                                                return knowledgeWebClient
                                                        .post()
                                                        .uri("/api/knowledge/ingest/upload")
                                                        .contentType(MediaType.MULTIPART_FORM_DATA)
                                                        .body(
                                                                BodyInserters.fromMultipartData(
                                                                        parts))
                                                        .retrieve()
                                                        .bodyToMono(String.class)
                                                        .map(
                                                                b ->
                                                                        ResponseEntity.accepted()
                                                                                .<String>body(b))
                                                        .onErrorResume(
                                                                WebClientResponseException.class,
                                                                e ->
                                                                        conflictOrUnavailable(
                                                                                e,
                                                                                "Ingest upload"));
                                            });
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get ingestion job status")
    @GetMapping("/{jobId}")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getJobStatus(@PathVariable UUID jobId) {
        return get("/api/knowledge/ingest/{jobId}", "Ingest status", jobId);
    }

    @Operation(summary = "Get ingestion job details")
    @GetMapping("/{jobId}/details")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getJobDetails(@PathVariable UUID jobId) {
        return get("/api/knowledge/ingest/{jobId}/details", "Job details", jobId);
    }

    @Operation(summary = "Delete an ingestion job and its chunks")
    @DeleteMapping("/{jobId}")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<Void>> deleteJob(@PathVariable UUID jobId) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .delete()
                                        .uri(
                                                b ->
                                                        KnowledgeTenantResolver.path(
                                                                b,
                                                                "/api/knowledge/ingest/{jobId}",
                                                                KnowledgeTenantResolver.resolve(
                                                                        ctx, null),
                                                                jobId))
                                        .retrieve()
                                        .toBodilessEntity()
                                        .map(r -> ResponseEntity.noContent().<Void>build())
                                        .onErrorResume(
                                                WebClientResponseException.NotFound.class,
                                                e ->
                                                        Mono.just(
                                                                ResponseEntity.notFound()
                                                                        .<Void>build()))
                                        .onErrorResume(
                                                e -> {
                                                    log.error(
                                                            "Job delete proxy error jobId={}: {}",
                                                            jobId,
                                                            e.getMessage());
                                                    return Mono.just(
                                                            ResponseEntity.status(
                                                                            HttpStatus
                                                                                    .SERVICE_UNAVAILABLE)
                                                                    .<Void>build());
                                                }))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Re-ingest a job (re-fetch URL or request file re-upload)")
    @PostMapping("/{jobId}/reingest")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> reingestJob(@PathVariable UUID jobId) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .post()
                                        .uri(
                                                b ->
                                                        KnowledgeTenantResolver.path(
                                                                b,
                                                                "/api/knowledge/ingest/{jobId}/reingest",
                                                                KnowledgeTenantResolver.resolve(
                                                                        ctx, null),
                                                                jobId))
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .map(body -> ResponseEntity.accepted().<String>body(body))
                                        .onErrorResume(
                                                WebClientResponseException.NotFound.class,
                                                e ->
                                                        Mono.just(
                                                                ResponseEntity.notFound()
                                                                        .<String>build()))
                                        .onErrorResume(
                                                e -> {
                                                    log.error(
                                                            "Reingest proxy error jobId={}: {}",
                                                            jobId,
                                                            e.getMessage());
                                                    return Mono.just(
                                                            ResponseEntity.status(
                                                                            HttpStatus
                                                                                    .SERVICE_UNAVAILABLE)
                                                                    .<String>build());
                                                }))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "List ingestion jobs")
    @GetMapping
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> listJobs(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenant;
                            try {
                                tenant =
                                        KnowledgeTenantResolver.resolve(
                                                ctx, tenantId == null ? null : tenantId.toString());
                            } catch (IllegalArgumentException e) {
                                return Mono.just(ResponseEntity.badRequest().<String>build());
                            }
                            return knowledgeWebClient
                                    .get()
                                    .uri(
                                            b ->
                                                    KnowledgeTenantResolver.path(
                                                            b.queryParam("page", page)
                                                                    .queryParam("size", size),
                                                            "/api/knowledge/ingest",
                                                            tenant))
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .map(ResponseEntity::ok)
                                    .onErrorResume(
                                            e -> {
                                                log.error(
                                                        "Ingest list proxy error: {}",
                                                        e.getMessage());
                                                return Mono.just(
                                                        ResponseEntity.status(
                                                                        HttpStatus
                                                                                .SERVICE_UNAVAILABLE)
                                                                .<String>build());
                                            });
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /** GET on an id-addressed ingestion resource, as the caller's tenant (KNOW-F1). */
    private Mono<ResponseEntity<String>> get(String path, String action, UUID jobId) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .get()
                                        .uri(
                                                b ->
                                                        KnowledgeTenantResolver.path(
                                                                b,
                                                                path,
                                                                KnowledgeTenantResolver.resolve(
                                                                        ctx, null),
                                                                jobId))
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .map(ResponseEntity::ok)
                                        .onErrorResume(
                                                WebClientResponseException.NotFound.class,
                                                e ->
                                                        Mono.just(
                                                                ResponseEntity.notFound()
                                                                        .<String>build()))
                                        .onErrorResume(
                                                e -> {
                                                    log.error(
                                                            "{} proxy error jobId={}: {}",
                                                            action,
                                                            jobId,
                                                            e.getMessage());
                                                    return Mono.just(
                                                            ResponseEntity.status(
                                                                            HttpStatus
                                                                                    .SERVICE_UNAVAILABLE)
                                                                    .<String>build());
                                                }))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    private Mono<ResponseEntity<String>> conflictOrUnavailable(
            WebClientResponseException e, String action) {
        if (e.getStatusCode().value() == 409) {
            return Mono.just(
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .<String>body(e.getResponseBodyAsString()));
        }
        log.error("{} proxy error: {}", action, e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<String>build());
    }
}
