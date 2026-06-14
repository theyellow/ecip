package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    private final KnowledgeDocumentRepository documentRepository;
    private final VectorSearchRepository vectorSearchRepository;
    private final KnowledgeExtractionService extractionService;
    private final LlmOrchestratorClient llmClient;

    @Transactional
    public List<UUID> ingestUrl(String url, UUID tenantId) {
        log.info("Ingesting URL: {}", url);

        String content = fetchUrl(url);
        String text = stripHtml(content);

        List<String> chunks = chunkText(text, CHUNK_SIZE, CHUNK_OVERLAP);
        List<UUID> documentIds = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setTenantId(tenantId);
            doc.setSourceType("URL");
            doc.setSourceRef(url);
            doc.setContent(chunks.get(i));
            doc.setChunkIndex(i);
            doc.setMetadata(Map.of("url", url, "chunkTotal", chunks.size()));
            KnowledgeDocument saved = documentRepository.save(doc);

            float[] embedding = llmClient.embed(chunks.get(i));
            if (embedding.length > 0) {
                vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
            }

            documentIds.add(saved.getId());
        }

        log.info("Ingested URL {} as {} chunks", url, chunks.size());
        return documentIds;
    }

    @Transactional
    public List<UUID> ingestText(String text, String sourceName, UUID tenantId) {
        List<String> chunks = chunkText(text, CHUNK_SIZE, CHUNK_OVERLAP);
        List<UUID> documentIds = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setTenantId(tenantId);
            doc.setSourceType("FILE_UPLOAD");
            doc.setSourceRef(sourceName);
            doc.setContent(chunks.get(i));
            doc.setChunkIndex(i);
            KnowledgeDocument saved = documentRepository.save(doc);

            float[] embedding = llmClient.embed(chunks.get(i));
            if (embedding.length > 0) {
                vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
            }

            documentIds.add(saved.getId());
        }

        log.info("Ingested text '{}' as {} chunks", sourceName, chunks.size());
        return documentIds;
    }

    List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String[] words = text.split("\\s+");
        int start = 0;
        while (start < words.length) {
            int end = Math.min(start + chunkSize, words.length);
            chunks.add(String.join(" ", java.util.Arrays.copyOfRange(words, start, end)));
            start += chunkSize - overlap;
        }
        return chunks;
    }

    private String fetchUrl(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch URL: " + url, e);
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
