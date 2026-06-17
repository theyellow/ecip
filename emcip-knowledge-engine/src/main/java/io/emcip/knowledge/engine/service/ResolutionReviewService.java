package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResolutionReviewService {

    private final ResolutionFlagRepository flagRepository;
    private final GraphRepository graphRepository;

    public Page<ResolutionFlag> list(
            String status, String conceptType, UUID tenantId, Pageable pageable) {
        return flagRepository.findFiltered(status, conceptType, tenantId, pageable);
    }

    @Transactional(rollbackFor = Exception.class)
    public void merge(UUID flagId) {
        ResolutionFlag flag =
                flagRepository
                        .findById(flagId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Flag not found: " + flagId));
        if (!"PENDING".equals(flag.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Flag is not PENDING: " + flag.getStatus());
        }
        // Graph operation first — throws propagate to trigger rollback before flag update
        graphRepository.mergeNodes(flag.getCandidateNodeId(), flag.getSimilarNodeId());
        flag.setStatus("MERGED");
        flagRepository.save(flag);
        log.info(
                "Merged node {} into {} (flag={})",
                flag.getCandidateNodeId(),
                flag.getSimilarNodeId(),
                flagId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void dismiss(UUID flagId) {
        ResolutionFlag flag =
                flagRepository
                        .findById(flagId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Flag not found: " + flagId));
        if (!"PENDING".equals(flag.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Flag is not PENDING: " + flag.getStatus());
        }
        flag.setStatus("DISMISSED");
        flagRepository.save(flag);
        log.info("Dismissed resolution flag {}", flagId);
    }
}
