package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.ConceptType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class ConceptTypeRepositoryTest {

    @Autowired private ConceptTypeRepository conceptTypeRepository;

    @Test
    void shouldSaveAndFindConceptType() {
        ConceptType type = new ConceptType();
        type.setName("TestConcept_" + UUID.randomUUID().toString().substring(0, 8));
        type.setDescription("A test concept");
        type.setProperties(List.of(Map.of("key", "testProp", "valueType", "STRING")));
        type.setShared(false);

        ConceptType saved = conceptTypeRepository.save(type);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getVersion()).isNotNull();
    }

    @Test
    void shouldFindByName() {
        String name = "FindByName_" + UUID.randomUUID().toString().substring(0, 8);
        ConceptType type = new ConceptType();
        type.setName(name);
        type.setDescription("test");
        type.setShared(false);
        conceptTypeRepository.save(type);

        assertThat(conceptTypeRepository.findByName(name)).isPresent();
        assertThat(conceptTypeRepository.findByName("nonexistent")).isEmpty();
    }
}
