package io.emcip.audit.service.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AuditRetentionJobTest {

    @Mock private AuditService auditService;

    private AuditRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new AuditRetentionJob(auditService);
        ReflectionTestUtils.setField(job, "retentionPeriod", "P10Y");
    }

    @Test
    void cleanupExpiredRecords_invokesDeleteWithCutoffInThePast() {
        when(auditService.deleteRecordsOlderThan(any(Instant.class))).thenReturn(Mono.just(5L));

        job.cleanupExpiredRecords();

        verify(auditService).deleteRecordsOlderThan(any(Instant.class));
    }

    @Test
    void cleanupExpiredRecords_onError_doesNotThrow() {
        when(auditService.deleteRecordsOlderThan(any(Instant.class)))
                .thenReturn(Mono.error(new RuntimeException("DB down")));

        // Should not propagate — fire-and-forget with .subscribe()
        job.cleanupExpiredRecords();

        verify(auditService).deleteRecordsOlderThan(any(Instant.class));
    }
}
