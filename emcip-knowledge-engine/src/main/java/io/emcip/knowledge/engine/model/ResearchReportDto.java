package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.ReportTemplate;
import io.emcip.knowledge.engine.entity.ResearchReport;
import java.time.Instant;
import java.util.UUID;

public record ResearchReportDto(
        UUID id,
        UUID tenantId,
        UUID sessionId,
        ReportTemplate template,
        String title,
        String content,
        int version,
        Instant createdAt) {

    public static ResearchReportDto from(ResearchReport r) {
        return new ResearchReportDto(
                r.getId(),
                r.getTenantId(),
                r.getSession().getId(),
                r.getTemplate(),
                r.getTitle(),
                r.getContent(),
                r.getVersion(),
                r.getCreatedAt());
    }
}
