package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.service.OntologyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Ontology", description = "Manage knowledge ontology (concept types and relationships)")
@RestController
@RequestMapping("/api/knowledge/ontology")
@RequiredArgsConstructor
public class OntologyController {

    private final OntologyService ontologyService;

    @Operation(summary = "List all concept types")
    @GetMapping("/concepts")
    public List<ConceptType> listConceptTypes() {
        return ontologyService.getAllConceptTypes();
    }

    @Operation(summary = "Create a new concept type")
    @PostMapping("/concepts")
    public ResponseEntity<ConceptType> createConceptType(@RequestBody ConceptType type) {
        ConceptType created = ontologyService.createConceptType(type);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "List all relationship types")
    @GetMapping("/relationships")
    public List<RelationshipType> listRelationshipTypes() {
        return ontologyService.getAllRelationshipTypes();
    }

    @Operation(summary = "Create a new relationship type")
    @PostMapping("/relationships")
    public ResponseEntity<RelationshipType> createRelationshipType(
            @RequestBody RelationshipType type) {
        RelationshipType created = ontologyService.createRelationshipType(type);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
