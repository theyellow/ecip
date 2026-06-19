package io.emcip.knowledge.engine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.entity.IngestionJob.IngestionStatus;
import io.emcip.knowledge.engine.entity.IngestionJob.SourceType;
import io.emcip.knowledge.engine.service.DocumentIngestionService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionControllerTest {

    @Mock DocumentIngestionService ingestionService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(new DocumentIngestionController(ingestionService))
                        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                        .build();
    }

    @Test
    void ingestUrl_returns202WithJobId() throws Exception {
        when(ingestionService.submitUrlIngestion(eq("https://example.com/article"), isNull()))
                .thenReturn("job-abc-123");

        mvc.perform(
                        post("/api/knowledge/ingest/url")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"url": "https://example.com/article"}
                                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-abc-123"));
    }

    @Test
    void ingestUpload_returns202WithJobId() throws Exception {
        when(ingestionService.submitFileIngestion(any(), eq("report.pdf"), isNull()))
                .thenReturn("job-def-456");

        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "report.pdf", "application/pdf", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/knowledge/ingest/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-def-456"));
    }

    @Test
    void getJob_returnsJobDto() throws Exception {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSourceType(SourceType.URL);
        job.setSourceRef("https://example.com/article");
        job.setStatus(IngestionStatus.COMPLETED);
        job.setChunkCount(7);
        job.setCreatedAt(OffsetDateTime.now());

        when(ingestionService.getJob(jobId)).thenReturn(job);

        mvc.perform(get("/api/knowledge/ingest/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.chunkCount").value(7))
                .andExpect(jsonPath("$.sourceRef").value("https://example.com/article"));
    }

    @Test
    void listJobs_returnsPage() throws Exception {
        IngestionJob job = new IngestionJob();
        job.setId(UUID.randomUUID());
        job.setSourceType(SourceType.FILE_UPLOAD);
        job.setSourceRef("report.pdf");
        job.setStatus(IngestionStatus.RUNNING);
        job.setCreatedAt(OffsetDateTime.now());

        when(ingestionService.listJobs(isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(job), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/knowledge/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.content[0].sourceRef").value("report.pdf"));
    }
}
