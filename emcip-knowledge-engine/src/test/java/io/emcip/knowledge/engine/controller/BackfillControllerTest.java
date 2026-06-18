package io.emcip.knowledge.engine.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.emcip.knowledge.engine.service.BackfillService;
import io.emcip.knowledge.engine.service.BackfillService.BackfillStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class BackfillControllerTest {

    @Mock BackfillService backfillService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BackfillController(backfillService)).build();
    }

    @Test
    void triggerBackfill_returns202WithBackfillId() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(backfillService.triggerBackfill(
                        eq(accountId), eq(-1001234567890L), eq(1_700_000_000L), eq(tenantId)))
                .thenReturn("backfill-abc-123");

        mvc.perform(
                        post("/api/knowledge/backfill")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "accountId": "%s",
                                          "chatId": -1001234567890,
                                          "tenantId": "%s",
                                          "fromDate": 1700000000
                                        }
                                        """
                                                .formatted(accountId, tenantId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.backfillId").value("backfill-abc-123"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getStatus_returnsRunningStatus() throws Exception {
        BackfillStatus running =
                new BackfillStatus(
                        "backfill-abc-123",
                        -1001234567890L,
                        "RUNNING",
                        42,
                        1_700_000_000L,
                        "2026-06-18T10:00:00Z",
                        null);
        when(backfillService.getStatus("backfill-abc-123")).thenReturn(running);

        mvc.perform(get("/api/knowledge/backfill/status").param("backfillId", "backfill-abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.processed").value(42))
                .andExpect(jsonPath("$.backfillId").value("backfill-abc-123"));
    }

    @Test
    void getStatus_returnsNotFoundStatusForUnknownId() throws Exception {
        String unknownId = UUID.randomUUID().toString();
        BackfillStatus notFound = new BackfillStatus(unknownId, 0L, "NOT_FOUND", 0, 0L, null, null);
        when(backfillService.getStatus(unknownId)).thenReturn(notFound);

        mvc.perform(get("/api/knowledge/backfill/status").param("backfillId", unknownId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }
}
