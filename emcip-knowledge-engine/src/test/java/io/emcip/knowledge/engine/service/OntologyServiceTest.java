package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.repository.ConceptTypeRepository;
import io.emcip.knowledge.engine.repository.RelationshipTypeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OntologyServiceTest {

    @Mock private ConceptTypeRepository conceptTypeRepository;
    @Mock private RelationshipTypeRepository relationshipTypeRepository;

    private OntologyService ontologyService;

    @BeforeEach
    void setUp() {
        ontologyService = new OntologyService(conceptTypeRepository, relationshipTypeRepository);
    }

    @Test
    void shouldReturnAllConceptTypes() {
        ConceptType person = new ConceptType();
        person.setName("Person");
        when(conceptTypeRepository.findAll()).thenReturn(List.of(person));

        List<ConceptType> result = ontologyService.getAllConceptTypes();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getName()).isEqualTo("Person");
    }

    @Test
    void shouldCreateConceptType() {
        ConceptType type = new ConceptType();
        type.setName("NewType");
        when(conceptTypeRepository.existsByName("NewType")).thenReturn(false);
        when(conceptTypeRepository.save(any())).thenReturn(type);

        ConceptType result = ontologyService.createConceptType(type);

        assertThat(result.getName()).isEqualTo("NewType");
        verify(conceptTypeRepository).save(type);
    }

    @Test
    void shouldRejectDuplicateConceptType() {
        ConceptType type = new ConceptType();
        type.setName("Person");
        when(conceptTypeRepository.existsByName("Person")).thenReturn(true);

        assertThatThrownBy(() -> ontologyService.createConceptType(type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Person");
    }

    @Test
    void shouldValidateRelationshipSourceTypes() {
        RelationshipType rel = new RelationshipType();
        rel.setName("DISCUSSES");
        rel.setSourceTypes(List.of("NonExistent"));
        rel.setTargetTypes(List.of("Topic"));
        when(relationshipTypeRepository.existsByName("DISCUSSES")).thenReturn(false);
        when(conceptTypeRepository.findByName("NonExistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ontologyService.createRelationshipType(rel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NonExistent");
    }
}
