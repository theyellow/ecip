package io.emcip.knowledge.engine.service;

import io.emcip.common.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class BackfillService {

    private static final String TOPIC_KNOWLEDGE_RAW = "knowledge.raw.messages";
    private static final int MAX_ITERATIONS = 5000;
    private static final long BATCH_DELAY_MS = 100;
    private static final ExecutorService BACKFILL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final RestClient tdlibRestClient;
    private final KnowledgeEventPublisher eventPublisher;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private final Map<String, BackfillStatus> activeBackfills = new ConcurrentHashMap<>();

    public BackfillService(
            @Value("${knowledge.tdlib-adapter.base-url:http://localhost:9080}") String tdlibBaseUrl,
            @Value("${admin.service-token}") String serviceToken,
            KnowledgeEventPublisher eventPublisher,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.tdlibRestClient =
                RestClient.builder()
                        .baseUrl(tdlibBaseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.eventPublisher = eventPublisher;
        this.kafkaTemplate = kafkaTemplate;
    }

    public String triggerBackfill(UUID accountId, long chatId, long fromDate, UUID tenantId) {
        String backfillId = UUID.randomUUID().toString();
        String startedAt = Instant.now().toString();

        activeBackfills.put(
                backfillId,
                new BackfillStatus(backfillId, chatId, "RUNNING", 0, fromDate, startedAt, null));

        log.info(
                "Backfill triggered: id={}, accountId={}, chatId={}, fromDate={}, tenantId={}",
                backfillId,
                accountId,
                chatId,
                fromDate,
                tenantId);

        BACKFILL_EXECUTOR.submit(
                () -> runBackfill(backfillId, accountId, chatId, fromDate, tenantId, startedAt));

        return backfillId;
    }

    private void runBackfill(
            String backfillId,
            UUID accountId,
            long chatId,
            long fromDate,
            UUID tenantId,
            String startedAt) {
        long offsetMessageId = 0L;
        int processed = 0;
        int iterations = 0;

        try {
            while (iterations < MAX_ITERATIONS) {
                iterations++;

                ChatHistoryResponse batch =
                        tdlibRestClient
                                .get()
                                .uri(
                                        "/internal/chat-history/{accountId}/{chatId}"
                                            + "?fromDate={fromDate}&limit=100&offsetMessageId={offset}",
                                        accountId,
                                        chatId,
                                        fromDate,
                                        offsetMessageId)
                                .retrieve()
                                .body(ChatHistoryResponse.class);

                if (batch == null || batch.messages() == null || batch.messages().isEmpty()) {
                    break;
                }

                for (String msgJson : batch.messages()) {
                    ProducerRecord<String, String> record =
                            new ProducerRecord<>(
                                    TOPIC_KNOWLEDGE_RAW, String.valueOf(chatId), msgJson);
                    if (tenantId != null) {
                        record.headers()
                                .add(
                                        TenantContext.KAFKA_HEADER,
                                        tenantId.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    kafkaTemplate.send(record);
                }

                processed += batch.messages().size();

                activeBackfills.put(
                        backfillId,
                        new BackfillStatus(
                                backfillId,
                                chatId,
                                "RUNNING",
                                processed,
                                fromDate,
                                startedAt,
                                null));

                eventPublisher.publishBackfillProgress(
                        String.valueOf(chatId), processed, -1, tenantId);

                log.debug(
                        "Backfill {}: published {} messages so far for chatId={}",
                        backfillId,
                        processed,
                        chatId);

                if (!batch.hasMore()) {
                    break;
                }

                long newOffset = batch.lastMessageId();
                if (newOffset == offsetMessageId) {
                    log.error(
                            "Backfill {}: pagination stuck at offsetMessageId={}, aborting",
                            backfillId,
                            offsetMessageId);
                    throw new IllegalStateException(
                            "Pagination stuck — offsetMessageId did not advance");
                }
                offsetMessageId = newOffset;

                Thread.sleep(BATCH_DELAY_MS);
            }

            if (iterations >= MAX_ITERATIONS) {
                log.warn(
                        "Backfill {}: hit max iterations ({}), processed={} messages",
                        backfillId,
                        MAX_ITERATIONS,
                        processed);
            }

            activeBackfills.put(
                    backfillId,
                    new BackfillStatus(
                            backfillId, chatId, "COMPLETED", processed, fromDate, startedAt, null));

            log.info(
                    "Backfill {} completed: chatId={}, totalProcessed={}",
                    backfillId,
                    chatId,
                    processed);

        } catch (Exception e) {
            log.error("Backfill {} failed: {}", backfillId, e.getMessage(), e);
            activeBackfills.put(
                    backfillId,
                    new BackfillStatus(
                            backfillId,
                            chatId,
                            "FAILED",
                            processed,
                            fromDate,
                            startedAt,
                            e.getMessage()));
        }
    }

    public BackfillStatus getStatus(String backfillId) {
        return activeBackfills.getOrDefault(
                backfillId, new BackfillStatus(backfillId, 0L, "NOT_FOUND", 0, 0L, null, null));
    }

    public record BackfillStatus(
            String backfillId,
            long chatId,
            String status,
            int processed,
            long fromDate,
            String startedAt,
            String errorMessage) {}

    /** Mirrors InternalController.ChatHistoryResponse from the tdlib-adapter. */
    public record ChatHistoryResponse(List<String> messages, boolean hasMore, long lastMessageId) {}
}
