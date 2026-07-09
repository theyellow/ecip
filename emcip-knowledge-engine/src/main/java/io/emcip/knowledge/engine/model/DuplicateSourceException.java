package io.emcip.knowledge.engine.model;

import java.util.UUID;
import lombok.Getter;

@Getter
public class DuplicateSourceException extends RuntimeException {

    private final UUID existingJobId;

    public DuplicateSourceException(String sourceRef, UUID existingJobId) {
        super("Already ingested: " + sourceRef + ". Use re-ingest to update.");
        this.existingJobId = existingJobId;
    }
}
