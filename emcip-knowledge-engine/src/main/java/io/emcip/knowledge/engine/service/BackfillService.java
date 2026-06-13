package io.emcip.knowledge.engine.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class BackfillService {

    private final RestClient tdlibRestClient;
    private final KnowledgeEventPublisher eventPublisher;

    private final Map<String, BackfillStatus> activeBackfills = new ConcurrentHashMap<>();

    public BackfillService(
            @Value("${knowledge.tdlib-adapter.base-url:http://localhost:9080}") String tdlibBaseUrl,
            KnowledgeEventPublisher eventPublisher) {
        this.tdlibRestClient = RestClient.builder().baseUrl(tdlibBaseUrl).build();
        this.eventPublisher = eventPublisher;
    }

    public String triggerBackfill(String accountId, long chatId, UUID tenantId) {
        String backfillId = UUID.randomUUID().toString();

        activeBackfills.put(backfillId, new BackfillStatus(backfillId, chatId, "RUNNING", 0, 0));

        log.info("Backfill triggered: id={}, chatId={}, tenantId={}", backfillId, chatId, tenantId);

        return backfillId;
    }

    public BackfillStatus getStatus(String backfillId) {
        return activeBackfills.getOrDefault(
                backfillId, new BackfillStatus(backfillId, 0, "NOT_FOUND", 0, 0));
    }

    public record BackfillStatus(
            String backfillId, long chatId, String status, int processed, int total) {}
}
