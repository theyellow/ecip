# Knowledge Management Platform Design

**Date**: 2026-06-13
**Backlog Items**: #26 (Knowledge Foundation), #27 (Deep Research Agent)
**Status**: Design approved

---

## Overview

A two-layer knowledge management platform for EMCIP that builds queryable knowledge from two sources:

- **Community Knowledge** — extracted from Telegram chat history (who discusses what, opinions, positions)
- **Factual Knowledge** — ingested from external documents (URLs, uploaded files — Wikipedia, papers, etc.)

The platform provides an ontology-driven knowledge graph, vector-based semantic search, and an autonomous research agent for operators.

---

## Architecture Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Infrastructure | PostgreSQL extensions (pgvector + Apache AGE) | No new server processes, reuses existing PostgreSQL |
| Abstraction | Interface layer (GraphRepository, VectorSearchRepository) | Swap to Neo4j/Qdrant later without touching business logic |
| Message pipeline | Separate `knowledge.raw.messages` Kafka topic | No interference with live moderation pipeline |
| Knowledge model | Ontology-driven, configuration-based | Evolves without schema migrations or code changes |
| Extraction approach | Entity-focused first, ontology grows naturally to include stance/sentiment | Solid foundation before tackling harder NLP problems |
| Service | New `emcip-knowledge-engine` (dedicated bounded context) | Clean separation from existing services |
| Query interface | REST API (operator) + Kafka events (service-to-service) | Matches existing architecture patterns |
| Processing | Immediate vector storage + synchronous LLM extraction; batched for backfill | Vector search works instantly, graph builds progressively |
| Multi-tenancy | Community knowledge tenant-isolated, factual knowledge shared | Public knowledge shouldn't be duplicated per tenant |
| LLM integration | Via llm-orchestrator REST API (new task types: EMBED, EXTRACT, RESOLVE) | Reuses model routing, cost tracking, prompt templates |

---

## Service Architecture

### New Service: `emcip-knowledge-engine`

- Spring Boot 4 / JPA (blocking), consistent with llm-orchestrator and policy-engine
- Own PostgreSQL schema with pgvector + Apache AGE extensions
- Kafka consumer for `knowledge.raw.messages`
- Kafka producer for `knowledge.events`
- REST API for queries, ingestion, ontology management, backfill

### Integration Points

```
tdlib-adapter ──publish──→ knowledge.raw.messages ──consume──→ knowledge-engine
admin-ui ──REST──→ admin-api ──forward──→ knowledge-engine REST API
llm-orchestrator ──kafka──→ knowledge.events (enrichment request/response)
knowledge-engine ──REST──→ llm-orchestrator (EMBED, EXTRACT, RESOLVE tasks)
```

---

## Data Model

### Ontology (configuration artifact, stored in DB)

```
ConceptType
  - id (UUID)
  - name (String, unique)            e.g., Person, Topic, Claim, Source, Document
  - description (String)
  - properties (JSONB)               property definitions: [{key, valueType, required}]
  - shared (boolean)                 true = cross-tenant factual, false = tenant-scoped
  - created_at, updated_at

RelationshipType
  - id (UUID)
  - name (String, unique)            e.g., DISCUSSES, HOLDS_STANCE, AUTHORED, CITES
  - description (String)
  - source_types (String[])          which ConceptTypes can be source
  - target_types (String[])          which ConceptTypes can be target
  - properties (JSONB)               property definitions: [{key, valueType}]
  - created_at, updated_at
```

### Initial Seed Ontology

**Concept Types**: Person, Topic, Message, Source, Document

**Relationship Types**:
- DISCUSSES (Person → Topic)
- AUTHORED (Person → Message)
- MENTIONS (Message → Topic)
- CITES (Document → Source)
- RELATED_TO (Topic → Topic)

### Graph Layer (Apache AGE)

```
GraphNode
  - id (UUID)
  - concept_type (String)            references ConceptType.name
  - tenant_id (UUID, nullable)       null = shared factual knowledge
  - label (String)                   display name
  - properties (JSONB)               dynamic, driven by ontology
  - embedding (vector)                node-level semantic search (dimension depends on model)
  - created_at, updated_at

GraphEdge
  - id (UUID)
  - relationship_type (String)       references RelationshipType.name
  - source_node_id (UUID)
  - target_node_id (UUID)
  - properties (JSONB)               e.g., {"sentiment": "positive", "confidence": 0.85}
  - source_message_id (UUID)         provenance — which message created this edge
  - created_at
```

### Vector Layer (pgvector)

```
KnowledgeDocument
  - id (UUID)
  - tenant_id (UUID, nullable)       null = shared factual knowledge
  - source_type (enum)               CHAT_MESSAGE, URL, FILE_UPLOAD
  - source_ref (String)              message ID, URL, or filename
  - content (text)                   raw text
  - chunk_index (int)                for long documents split into chunks
  - embedding (vector)                dimension depends on embedding model         semantic search vector
  - metadata (JSONB)                 author, timestamp, group, etc.
  - created_at
```

### Abstraction Interfaces

```java
public interface GraphRepository {
    GraphNode createNode(ConceptType type, String label,
                         Map<String, Object> properties, UUID tenantId);
    GraphEdge createRelationship(RelationshipType type,
                                 UUID sourceId, UUID targetId,
                                 Map<String, Object> properties);
    List<GraphNode> findConnected(UUID nodeId, RelationshipType relType, int depth);
    List<GraphNode> matchPattern(GraphQuery query);
    Optional<GraphNode> findByLabelAndType(String label, String conceptType, UUID tenantId);
}

public interface VectorSearchRepository {
    void store(KnowledgeDocument document);
    List<KnowledgeDocument> search(float[] queryEmbedding, int topK, UUID tenantId);
    List<KnowledgeDocument> hybridSearch(String query, float[] embedding,
                                          int topK, UUID tenantId);
}
```

Implementations: `AgeGraphRepository`, `PgVectorSearchRepository`.

---

## Processing Pipeline

### Kafka Topics

| Topic | Purpose | Producers | Consumers |
|-------|---------|-----------|-----------|
| `knowledge.raw.messages` | Input for knowledge extraction | tdlib-adapter (live fork + backfill) | knowledge-engine |
| `knowledge.events` | Results, progress, enrichment | knowledge-engine | admin-api, llm-orchestrator |

### Live Message Processing

```
knowledge.raw.messages
  → KnowledgeMessageConsumer
      1. Store raw content as KnowledgeDocument + generate embedding via llm-orchestrator
         (vector search works immediately)
      2. Call llm-orchestrator (EXTRACT task) for entity/relationship extraction
         - Prompt driven by ontology definition
         - LLM returns structured JSON
      3. Entity resolution (deduplicate)
      4. Write nodes + edges to graph (Apache AGE)
      5. Publish extraction result to knowledge.events
```

### Entity Resolution (3-level)

1. **Exact match** — normalized lowercase label lookup against existing nodes
2. **Alias table** — maintained per concept type (e.g., "AI" → "Artificial Intelligence")
3. **Embedding similarity** — vector similarity against existing nodes of same type; above threshold → merge, below → create new; ambiguous cases flagged for operator review

### Bulk Backfill

```
Operator triggers via admin-ui
  → admin-api → tdlib-adapter REST endpoint
  → tdlib-adapter pages through TDLib getChatHistory()
  → Publishes batches to knowledge.raw.messages
  → knowledge-engine processes with batched LLM extraction (10-20 messages per call)
  → Progress events on knowledge.events
  → admin-ui shows progress
```

### Document Ingestion (Factual Knowledge)

```
Operator submits URL or uploads file via admin-ui
  → admin-api → knowledge-engine REST API
  → Fetch URL / read file → extract text (HTML/PDF parsing)
  → Chunk into ~500-token overlapping segments
  → Store each chunk as KnowledgeDocument (tenant_id = null for shared)
  → Generate embeddings
  → LLM extraction: entities + relationships per chunk
  → Entity resolution + graph storage
  → Publish completion to knowledge.events
```

### LLM Integration

New task types in llm-orchestrator:

| Task Type | Purpose |
|-----------|---------|
| `EMBED` | Generate embedding vector for text |
| `EXTRACT` | Extract entities and relationships from text (ontology-driven prompt) |
| `RESOLVE` | Disambiguate entity when alias/exact match fails |

### Error Handling

- LLM unavailable → Kafka consumer backs off, message stays on topic (automatic retry)
- Invalid extraction JSON → message sent to DLQ (`knowledge.raw.messages.dlq`), processing continues
- Ambiguous entity resolution → stored as new node, flagged for operator review

---

## Query & API Layer

### Search API

```
POST /api/knowledge/search
  {
    "query": "Who discusses AI in group X?",
    "searchType": "HYBRID",           // GRAPH, VECTOR, HYBRID
    "tenantId": "...",
    "filters": {
      "conceptTypes": ["Person"],
      "timeRange": {"from": "...", "to": "..."},
      "sourceTypes": ["CHAT_MESSAGE"]
    },
    "limit": 20
  }

Response:
  {
    "graphResults": [
      { "node": {...}, "connections": [...], "score": 0.92 }
    ],
    "documentResults": [
      { "content": "...", "source": {...}, "similarity": 0.87 }
    ]
  }
```

### Hybrid Query Processing

1. **Vector search** — embed query → cosine similarity → top-K documents/nodes
2. **Graph traversal** — from matched nodes, traverse relationships for connected entities
3. **Merge & rank** — combine results by relevance (vector similarity + graph distance)

### Additional Endpoints

```
# Ontology management
GET    /api/knowledge/ontology/concepts
POST   /api/knowledge/ontology/concepts
GET    /api/knowledge/ontology/relationships
POST   /api/knowledge/ontology/relationships

# Document ingestion
POST   /api/knowledge/ingest/url
POST   /api/knowledge/ingest/upload

# Backfill
POST   /api/knowledge/backfill
GET    /api/knowledge/backfill/status

# Graph exploration
GET    /api/knowledge/graph/node/{id}
GET    /api/knowledge/graph/node/{id}/neighbors
GET    /api/knowledge/graph/topics
GET    /api/knowledge/graph/persons

# Knowledge events
GET    /api/knowledge/events?since=...
```

### Kafka Events (service-to-service enrichment)

```
KnowledgeEnrichmentRequest  (llm-orchestrator → knowledge.events)
  → "Enrich this message with community context"

KnowledgeEnrichmentResponse (knowledge-engine → knowledge.events)
  → relevant entities, relationships, and document snippets
```

---

## Epic & User Stories

### Epic: Knowledge Management Platform

**As an** EMCIP operator, **I want** a queryable knowledge base built from community chat history and external documents, **so that** I can understand what topics are discussed, who holds which positions, and research questions using both community and factual knowledge.

---

### #26 — Knowledge Foundation

#### US-26.1: PostgreSQL Extensions & Abstraction Layer
As a developer, I want pgvector and Apache AGE enabled in our PostgreSQL instance with abstraction interfaces, so that we have graph and vector capabilities with the option to swap implementations later.

- Enable pgvector + Apache AGE extensions (Liquibase or manual setup)
- `GraphRepository` interface + `AgeGraphRepository` implementation
- `VectorSearchRepository` interface + `PgVectorSearchRepository` implementation
- Liquibase migrations for extension setup
- Integration tests verifying both layers

#### US-26.2: Knowledge Engine Service Bootstrap
As a developer, I want a new `emcip-knowledge-engine` Spring Boot service, so that knowledge management has its own bounded context.

- New Maven module with Spring Boot 4, JPA, Kafka consumer
- Port assignment, Docker configuration, health indicator
- Kafka consumer for `knowledge.raw.messages` topic
- Basic service structure (controller, service, repository layers)
- Helm chart entry, Prometheus metrics endpoint

#### US-26.3: Ontology Model
As a developer, I want a configurable ontology that defines concept types and relationship types, so that knowledge extraction is driven by configuration rather than hardcoded logic.

- `ConceptType` and `RelationshipType` JPA entities
- CRUD REST API for ontology management
- Initial seed ontology: Person, Topic, Message, Source, Document + DISCUSSES, AUTHORED, MENTIONS, CITES, RELATED_TO
- Ontology validation (relationship source/target type constraints)
- Liquibase migrations + seed data

#### US-26.4: Knowledge Extraction Pipeline
As a developer, I want incoming messages to be processed into graph entities and vector embeddings, so that community knowledge becomes searchable.

- `KnowledgeMessageConsumer` consuming from `knowledge.raw.messages`
- Raw message → `KnowledgeDocument` storage + embedding generation (immediate vector searchability)
- LLM-based entity/relationship extraction via llm-orchestrator API (new EXTRACT task type)
- Structured JSON response parsing → graph node/edge creation
- Ontology-driven extraction prompts
- DLQ for failed extractions

#### US-26.5: Entity Resolution
As a developer, I want extracted entities to be deduplicated and merged, so that the knowledge graph doesn't contain redundant nodes.

- Exact match (normalized lowercase)
- Alias table per concept type (configurable)
- Embedding similarity fallback (above threshold → merge)
- Operator review queue for ambiguous matches
- LLM-assisted resolution via llm-orchestrator (RESOLVE task type)

#### US-26.6: Live Message Fork
As a developer, I want live Telegram messages to flow into the knowledge pipeline alongside the existing processing pipeline, so that the knowledge base grows continuously.

- tdlib-adapter publishes to `knowledge.raw.messages` in addition to `telegram.raw.messages`
- Same message format, no changes to existing consumers
- Configurable per tenant (opt-in/opt-out)

#### US-26.7: Bulk Backfill
As an operator, I want to trigger a historical message backfill for a Telegram group, so that existing chat history becomes part of the knowledge base.

- REST endpoint on tdlib-adapter: fetch chat history via TDLib `getChatHistory`
- Paging through history, publishing batches to `knowledge.raw.messages`
- Batched LLM extraction (10-20 messages per call) for cost efficiency
- Progress tracking via `knowledge.events`
- Admin-ui: backfill trigger button + progress indicator

#### US-26.8: Document Ingestion (Factual Knowledge)
As an operator, I want to submit URLs or upload documents to build a factual knowledge base, so that research queries can draw from authoritative sources.

- REST endpoints: `POST /ingest/url` and `POST /ingest/upload`
- URL fetching + HTML/PDF text extraction
- Chunking (overlapping windows, ~500 tokens)
- Embedding generation + `KnowledgeDocument` storage (tenant_id = null for shared)
- LLM entity extraction from chunks → graph storage
- Admin-ui: URL input form + file upload

#### US-26.9: Knowledge Query API
As an operator, I want to search the knowledge base using natural language, graph traversal, or hybrid queries, so that I can explore community and factual knowledge.

- `POST /search` with GRAPH, VECTOR, HYBRID modes
- Vector search: embed query → cosine similarity → top-K documents
- Graph traversal: from matched nodes → follow relationships
- Hybrid: combine + rank by relevance
- Tenant isolation (community knowledge) + shared access (factual knowledge)
- Graph exploration endpoints (topics, persons, node neighbors)

#### US-26.10: Knowledge Enrichment for LLM Responses
As a developer, I want llm-orchestrator to enrich its prompts with knowledge context, so that generated responses are informed by community and factual knowledge.

- `KnowledgeEnrichmentRequest` / `KnowledgeEnrichmentResponse` on `knowledge.events` topic
- llm-orchestrator publishes enrichment request before generating response
- knowledge-engine returns relevant entities, relationships, and document snippets
- llm-orchestrator incorporates context into prompt template

---

### #27 — Deep Research Agent

#### US-27.1: Research Agent Core & Strategy Engine
As a developer, I want an autonomous research agent that decomposes a question into sub-tasks and executes them, so that complex research runs without manual step-by-step guidance.

- Research session entity (tracks state, sub-questions, intermediate results, cost)
- Strategy engine: given a research question, decompose into sub-questions using LLM
- Execution loop: for each sub-question → select source (knowledge graph, vector search, web) → query → evaluate → decide if more research needed
- Configurable depth limits (max iterations, max LLM calls, max cost per session)
- Termination criteria: confidence threshold reached, cost limit hit, or max depth

#### US-27.2: Knowledge Base Query Strategies
As a developer, I want the research agent to query the knowledge base using different strategies depending on the question type, so that it finds the most relevant information.

- **Topic exploration** — "What do we know about X?" → graph traversal from topic node + vector search
- **Person analysis** — "What does Person X discuss/think?" → graph edges from person + authored messages
- **Opinion mapping** — "Who holds what position on X?" → persons connected to topic via HOLDS_STANCE
- **Comparison** — "How do opinions differ between Group A and Group B?" → scoped graph queries
- **Fact verification** — "Is claim X supported?" → search factual knowledge, compare with community claims
- Strategy selection: LLM chooses strategy based on question classification

#### US-27.3: Web Search Integration
As a developer, I want the research agent to search the web for additional information, so that reports aren't limited to existing knowledge base content.

- Web search API integration (self-hosted SearXNG preferred, Brave API as fallback)
- Search result fetching, content extraction, relevance scoring
- Results optionally stored as factual knowledge documents (operator approval)
- Rate limiting and cost tracking for external API calls

#### US-27.4: Evidence Collection & Provenance
As a developer, I want every research finding linked to its source, so that the operator can verify claims and trace them back to original messages or documents.

- Evidence entity in the ontology (Claim, Evidence, Conclusion)
- Each finding carries: source reference, confidence score, extraction method
- Contradictory evidence flagged explicitly
- Provenance chain: conclusion → supporting evidence → original source

#### US-27.5: Report Generation & Templates
As an operator, I want research results as a structured, readable report, so that I can quickly understand findings without reading raw data.

- LLM-generated report from collected evidence
- Report sections: Executive Summary, Key Findings, Community Perspective, Factual Context, Contradictions & Open Questions, Sources
- Report templates configurable per research type (topic report, person report, fact-check)
- Reports stored as knowledge artifacts in the graph
- Export: rendered in admin-ui, downloadable as Markdown

#### US-27.6: Operator Interaction During Research
As an operator, I want to monitor research progress and steer the agent, so that I can guide it when it goes in the wrong direction.

- Live progress stream via `knowledge.events` (current sub-question, findings so far, cost spent)
- Admin-ui: research session view with real-time progress
- Operator can: pause, add hints/constraints ("focus on Group X", "ignore before 2025"), resume
- Operator can approve/reject intermediate findings before agent continues

#### US-27.7: Research Triggers & Integration
As an operator, I want to trigger research from different contexts, so that it integrates naturally into my workflow.

- Trigger from admin-ui: free-form question input
- Trigger from flagged message: "Research context around this flagged message"
- Trigger from topic view: "Deep dive on this topic"
- Trigger from person view: "Analyze this person's positions"
- Scheduled research: periodic re-analysis of evolving topics (e.g., weekly opinion shift report)

#### US-27.8: Research History, Iteration & Comparison
As an operator, I want to revisit past research, refine it, and compare results over time, so that research is cumulative and tracks evolution.

- Research session history list with status, cost, date
- Follow-up questions that build on existing session context
- Report versioning (v1, v2, ... as new evidence is added)
- Comparison view: opinion shifts on Topic X between two research runs
- Archiving and deletion of outdated research

#### US-27.9: Cost Management & Guardrails
As an operator, I want visibility and control over research costs, so that an autonomous agent doesn't consume excessive LLM resources.

- Per-session cost tracking (LLM calls, embeddings, web searches)
- Configurable budgets: per-session limit, daily limit, per-tenant limit
- Cost estimate before starting ("~15 LLM calls, estimated cost: X")
- Auto-pause when budget threshold reached, operator must approve continuation
- Cost dashboard in admin-ui

---

## Future Backlog Items (out of scope)

- **Structured feed connectors** — Predefined source connectors (Wikipedia API, arXiv, PubMed) for automated periodic ingestion of factual knowledge
- **Stance/sentiment extraction** — Ontology extension to extract opinions and positions (natural evolution of entity-focused extraction)
- **Opinion evolution tracking** — Track how persons' positions on topics change over time
- **Agreement/disagreement inference** — Automatically detect when persons agree or disagree on topics

---

## Infrastructure Requirements

### PostgreSQL Extensions

- **pgvector** — vector similarity search (cosine, L2, inner product)
- **Apache AGE** — graph database with openCypher query language

Both require PostgreSQL 16 (current version). May require custom Docker image or extension installation.

### New Kafka Topics

- `knowledge.raw.messages` — input for knowledge extraction
- `knowledge.raw.messages.dlq` — dead letter queue for failed extractions
- `knowledge.events` — results, progress, enrichment events

### New LLM Task Types (in llm-orchestrator)

- `EMBED` — generate embedding vectors
- `EXTRACT` — ontology-driven entity/relationship extraction
- `RESOLVE` — entity disambiguation

### New Service Port

- `emcip-knowledge-engine` — port TBD (next available in the 908x range)