package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedRelationship;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeExtractionServiceTest {

    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private VectorSearchRepository vectorSearchRepository;
    @Mock private GraphRepository graphRepository;
    @Mock private EntityResolutionService entityResolutionService;
    @Mock private LlmOrchestratorClient llmClient;
    @Mock private OntologyService ontologyService;
    @Mock private KnowledgeEventPublisher eventPublisher;

    private KnowledgeExtractionService service;

    @BeforeEach
    void setUp() {
        service =
                new KnowledgeExtractionService(
                        documentRepository,
                        vectorSearchRepository,
                        graphRepository,
                        entityResolutionService,
                        llmClient,
                        ontologyService,
                        eventPublisher);
    }

    @Test
    void shouldStoreDocumentAndExtractEntities() {
        UUID tenantId = UUID.randomUUID();
        String text = "Alice discussed AI with Bob";
        String sourceRef = "msg-42";

        when(llmClient.embed(text)).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(documentRepository.saveAndFlush(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });

        var entities =
                List.of(
                        new ExtractedEntity("Person", "Alice", Map.of()),
                        new ExtractedEntity("Person", "Bob", Map.of()),
                        new ExtractedEntity("Topic", "AI", Map.of()));
        var relationships =
                List.of(
                        new ExtractedRelationship("DISCUSSES", "Alice", "AI", Map.of()),
                        new ExtractedRelationship("DISCUSSES", "Bob", "AI", Map.of()));
        ConceptType personType = new ConceptType();
        personType.setName("Person");
        personType.setDescription("A human");
        personType.setShared(false);
        ConceptType topicType = new ConceptType();
        topicType.setName("Topic");
        topicType.setDescription("A subject");
        topicType.setShared(false);
        when(ontologyService.getAllConceptTypes()).thenReturn(List.of(personType, topicType));

        RelationshipType discussesType = new RelationshipType();
        discussesType.setName("DISCUSSES");
        discussesType.setDescription("Discusses");
        discussesType.setSourceTypes(List.of("Person"));
        discussesType.setTargetTypes(List.of("Topic"));
        when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of(discussesType));

        when(llmClient.extract(eq(text), any(), any()))
                .thenReturn(new ExtractionResult(entities, relationships));

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID aiId = UUID.randomUUID();
        // Entity loop stubs
        when(entityResolutionService.resolve("Alice", "Person", tenantId)).thenReturn(aliceId);
        when(entityResolutionService.resolve("Bob", "Person", tenantId)).thenReturn(bobId);
        when(entityResolutionService.resolve("AI", "Topic", tenantId)).thenReturn(aiId);
        // Relationship loop stubs — inferType returns "Topic" for both sides (ontologyService not
        // mocked, getRelationshipType throws, caught → "Topic")
        when(entityResolutionService.resolve("Alice", "Topic", tenantId)).thenReturn(aliceId);
        when(entityResolutionService.resolve("Bob", "Topic", tenantId)).thenReturn(bobId);

        service.processMessage(
                text, sourceRef, tenantId, 100L, "999", "TestUser", "TestGroup", 1718272800);

        verify(documentRepository).saveAndFlush(any());
        verify(vectorSearchRepository).storeEmbedding(any(), any());
        verify(graphRepository)
                .createRelationship(eq("DISCUSSES"), eq(aliceId), eq(aiId), any(), any());
        verify(graphRepository)
                .createRelationship(eq("DISCUSSES"), eq(bobId), eq(aiId), any(), any());
    }

    @Test
    void shouldPopulateMetadataOnDocument() {
        UUID tenantId = UUID.randomUUID();
        String text = "Alice met Bob";
        String sourceRef = "tg:100:42";

        when(llmClient.embed(text)).thenReturn(new float[] {0.1f});
        when(documentRepository.saveAndFlush(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });
        when(ontologyService.getAllConceptTypes()).thenReturn(List.of());
        when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
        when(llmClient.extract(eq(text), any(), any()))
                .thenReturn(new ExtractionResult(List.of(), List.of()));

        service.processMessage(
                text, sourceRef, tenantId, 100L, "999", "TestUser", "TestGroup", 1718272800);

        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentRepository).saveAndFlush(captor.capture());
        KnowledgeDocument saved = captor.getValue();
        assertThat(saved.getMetadata()).isNotNull();
        assertThat(saved.getMetadata()).containsEntry("chatId", 100L);
        assertThat(saved.getMetadata()).containsEntry("senderId", "999");
        assertThat(saved.getMetadata()).containsEntry("senderDisplayName", "TestUser");
        assertThat(saved.getMetadata()).containsEntry("chatTitle", "TestGroup");
        assertThat(saved.getMetadata()).containsEntry("messageDate", 1718272800);
    }

    @Test
    void shouldSkipEntityWithNullType() {
        UUID tenantId = UUID.randomUUID();
        String text = "entity with null type";
        when(llmClient.embed(text)).thenReturn(new float[] {0.1f});
        when(documentRepository.saveAndFlush(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });
        when(ontologyService.getAllConceptTypes()).thenReturn(List.of());
        when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
        when(llmClient.extract(any(), any(), any()))
                .thenReturn(
                        new ExtractionResult(
                                List.of(new ExtractedEntity(null, "Alice", Map.of())), List.of()));

        service.processMessage(text, "tg:1:1", tenantId, 1L, "1", "Alice", "G", 0);

        verifyNoInteractions(entityResolutionService);
        verifyNoInteractions(graphRepository);
    }

    @Test
    void shouldSkipEntityWithUnknownType() {
        UUID tenantId = UUID.randomUUID();
        String text = "entity with unknown type";
        ConceptType person = new ConceptType();
        person.setName("PERSON");
        person.setDescription("A human");
        person.setShared(false);
        when(llmClient.embed(text)).thenReturn(new float[] {0.1f});
        when(documentRepository.saveAndFlush(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });
        when(ontologyService.getAllConceptTypes()).thenReturn(List.of(person));
        when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
        when(llmClient.extract(any(), any(), any()))
                .thenReturn(
                        new ExtractionResult(
                                List.of(new ExtractedEntity("INVENTED_TYPE", "Alice", Map.of())),
                                List.of()));

        service.processMessage(text, "tg:1:1", tenantId, 1L, "1", "Alice", "G", 0);

        verifyNoInteractions(entityResolutionService);
        verifyNoInteractions(graphRepository);
    }

    @Test
    void shouldSkipRelationshipWithUnknownType() {
        UUID tenantId = UUID.randomUUID();
        String text = "relationship with unknown type";
        ConceptType person = new ConceptType();
        person.setName("PERSON");
        person.setDescription("A human");
        person.setShared(false);
        RelationshipType knows = new RelationshipType();
        knows.setName("KNOWS");
        knows.setDescription("Knows");
        knows.setSourceTypes(List.of("PERSON"));
        knows.setTargetTypes(List.of("PERSON"));
        when(llmClient.embed(text)).thenReturn(new float[] {0.1f});
        when(documentRepository.saveAndFlush(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });
        when(ontologyService.getAllConceptTypes()).thenReturn(List.of(person));
        when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of(knows));
        UUID aliceId = UUID.randomUUID();
        when(entityResolutionService.resolve(eq("Alice"), eq("PERSON"), eq(tenantId)))
                .thenReturn(aliceId);
        when(llmClient.extract(any(), any(), any()))
                .thenReturn(
                        new ExtractionResult(
                                List.of(new ExtractedEntity("PERSON", "Alice", Map.of())),
                                List.of(
                                        new ExtractedRelationship(
                                                "INVENTED_REL", "Alice", "Bob", Map.of()))));

        service.processMessage(text, "tg:1:1", tenantId, 1L, "1", "Alice", "G", 0);

        verifyNoInteractions(graphRepository);
    }
}
