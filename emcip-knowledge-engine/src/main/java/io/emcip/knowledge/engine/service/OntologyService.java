package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.repository.ConceptTypeRepository;
import io.emcip.knowledge.engine.repository.RelationshipTypeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OntologyService {

    private final ConceptTypeRepository conceptTypeRepository;
    private final RelationshipTypeRepository relationshipTypeRepository;

    public List<ConceptType> getAllConceptTypes() {
        return conceptTypeRepository.findAll();
    }

    public List<RelationshipType> getAllRelationshipTypes() {
        return relationshipTypeRepository.findAll();
    }

    @Transactional
    public ConceptType createConceptType(ConceptType type) {
        if (conceptTypeRepository.existsByName(type.getName())) {
            throw new IllegalArgumentException("Concept type already exists: " + type.getName());
        }
        log.info("Creating concept type: {}", type.getName());
        return conceptTypeRepository.save(type);
    }

    @Transactional
    public RelationshipType createRelationshipType(RelationshipType type) {
        if (relationshipTypeRepository.existsByName(type.getName())) {
            throw new IllegalArgumentException(
                    "Relationship type already exists: " + type.getName());
        }
        for (String sourceType : type.getSourceTypes()) {
            if (conceptTypeRepository.findByName(sourceType).isEmpty()) {
                throw new IllegalArgumentException("Source concept type not found: " + sourceType);
            }
        }
        for (String targetType : type.getTargetTypes()) {
            if (conceptTypeRepository.findByName(targetType).isEmpty()) {
                throw new IllegalArgumentException("Target concept type not found: " + targetType);
            }
        }
        log.info("Creating relationship type: {}", type.getName());
        return relationshipTypeRepository.save(type);
    }

    public ConceptType getConceptType(String name) {
        return conceptTypeRepository
                .findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Concept type not found: " + name));
    }

    public RelationshipType getRelationshipType(String name) {
        return relationshipTypeRepository
                .findByName(name)
                .orElseThrow(
                        () -> new IllegalArgumentException("Relationship type not found: " + name));
    }
}
