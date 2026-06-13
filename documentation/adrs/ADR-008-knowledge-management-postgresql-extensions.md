# ADR-008: Knowledge Management with PostgreSQL Extensions (pgvector + Apache AGE)

## Status

**Accepted**

Date: 2026-06-13

---

## Context

EMCIP backlog items #26 (Bulk message ingestion + topic clustering + RAG) and #27 (Deep research operator tool) require two new data capabilities that the current PostgreSQL 16 setup does not provide:

1. **Vector similarity search** — storing and querying embedding vectors for semantic search over messages and documents (RAG foundation).
2. **Graph database** — modeling relationships between people, topics, opinions, and claims extracted from community chat messages (Community Knowledge) and external documents (Factual Knowledge).

The external research recommended dedicated database engines: Neo4j for graph and Qdrant for vector search. However, EMCIP runs on a single-node microk8s cluster with limited operational capacity. Adding two new database servers would significantly increase infrastructure complexity, backup procedures, and monitoring overhead.

---

## Decision

Use **PostgreSQL extensions** instead of dedicated database engines:

* **pgvector** — vector similarity search (cosine, L2, inner product) with IVFFlat and HNSW indexing.
* **Apache AGE** (A Graph Extension) — graph database with openCypher query language (same as Neo4j) running inside PostgreSQL.

Both extensions run within the existing PostgreSQL 16 instance. No new server processes are required.

Business logic accesses graph and vector capabilities through **abstraction interfaces** (`GraphRepository`, `VectorSearchRepository`) so that dedicated engines (Neo4j, Qdrant) can be swapped in later without changing application code.

---

## Rationale

### 1. Operational Simplicity

The current infrastructure runs one PostgreSQL instance serving all 9 microservices. Adding pgvector and Apache AGE keeps this to one instance. Backup, monitoring, and failover procedures remain unchanged.

### 2. pgvector Maturity

pgvector is production-ready and widely adopted. It supports HNSW indexing (approximate nearest neighbor) which provides sub-millisecond query times for datasets up to millions of vectors. For EMCIP's alpha-phase community-sized data, this is more than sufficient.

### 3. Apache AGE and openCypher Compatibility

Apache AGE implements the openCypher query language — the same language used by Neo4j. This means:
* Graph queries written for AGE can be ported to Neo4j with minimal changes.
* Developers familiar with Neo4j (Cypher) can work with AGE immediately.
* The abstraction layer (`GraphRepository`) can have both an `AgeGraphRepository` and a future `Neo4jGraphRepository` implementation.

### 4. Abstraction Layer for Future Migration

The decision to start with PostgreSQL extensions is not permanent. The `GraphRepository` and `VectorSearchRepository` interfaces define a small, well-bounded surface area:

* `GraphRepository`: createNode, createRelationship, findConnected, matchPattern, findByLabelAndType
* `VectorSearchRepository`: store, search, hybridSearch

If query performance or feature requirements outgrow PostgreSQL extensions, a new implementation backed by Neo4j or Qdrant can be added without touching business logic, extraction pipelines, or query services.

### 5. Alpha Phase Flexibility

EMCIP is in alpha with no production data. The knowledge management system is being built from scratch. Starting lean and upgrading later is lower risk than over-engineering infrastructure that may not be needed.

---

## Consequences

**Positive:**
* Zero new infrastructure servers — no additional operational overhead.
* Single backup target — PostgreSQL dump captures everything (relational, graph, and vector data).
* Familiar query patterns — openCypher for graph, SQL with vector operators for similarity search.
* Migration path preserved — abstraction interfaces allow swapping to dedicated engines.

**Negative:**
* At very large scale (millions of vectors, deep graph traversals with 5+ hops), dedicated engines will outperform PostgreSQL extensions.
* Apache AGE is less mature than Neo4j — some advanced Cypher features may not be available.
* pgvector HNSW index builds can be memory-intensive for very large vector collections.

---

## Alternatives Considered

| Alternative | Reason Rejected |
|------------|-----------------|
| Neo4j + Qdrant (dedicated engines) | Two new servers, significant ops overhead for a single-node cluster in alpha |
| Neo4j only (graph + property-based search) | No native vector similarity search; would still need pgvector or Qdrant for RAG |
| Qdrant only (vector search + metadata filtering) | No graph traversal capability; person-topic-opinion relationships are fundamentally graph queries |
| Milvus (vector DB) | Heavier than Qdrant, more complex to operate, same problem of adding a new server |

---

## Related ADRs

* ADR-003: Data Persistence with PostgreSQL (PostgreSQL as primary database)
* ADR-004: R2DBC and Reactive Stack for Phase 4 Services (persistence split strategy)

## Related Specs

* `docs/superpowers/specs/2026-06-13-knowledge-management-platform-design.md`
