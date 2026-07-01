package io.emcip.audit.service.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.audit.service.service.AuditService.ChainVerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AuditChainVerificationJobTest {

    @Mock private AuditService auditService;

    private AuditChainVerificationJob job;

    @BeforeEach
    void setUp() {
        job = new AuditChainVerificationJob(auditService);
        ReflectionTestUtils.setField(job, "batchSize", 1000);
    }

    @Test
    void verifyRecentChain_validChain_logsSuccess() {
        when(auditService.verifyChain(1000)).thenReturn(Mono.just(ChainVerificationResult.ok(100)));

        job.verifyRecentChain();

        verify(auditService).verifyChain(1000);
    }

    @Test
    void verifyRecentChain_brokenChain_doesNotThrow() {
        when(auditService.verifyChain(1000))
                .thenReturn(
                        Mono.just(
                                ChainVerificationResult.broken(
                                        50, 42L, "expectedHash", "actualHash")));

        // Should not propagate — fire-and-forget with .subscribe()
        job.verifyRecentChain();

        verify(auditService).verifyChain(1000);
    }

    @Test
    void verifyRecentChain_onError_doesNotThrow() {
        when(auditService.verifyChain(1000))
                .thenReturn(Mono.error(new RuntimeException("DB unavailable")));

        job.verifyRecentChain();

        verify(auditService).verifyChain(1000);
    }
}
