package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.AuditEvent;
import io.emcip.admin.api.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditEventRepository auditEventRepository;

    @GetMapping("/events")
    public Flux<AuditEvent> getEvents(@RequestParam(name = "size", defaultValue = "50") int size) {
        return auditEventRepository.findRecent(size);
    }
}
