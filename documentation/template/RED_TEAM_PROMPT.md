# Red Team Review — Prompt Template

> Paste this whole file into Claude Code as your instruction. Adjust the
> `<<SCOPE>>` line and severity bar if you want to narrow a run. The review is
> **read-only and advisory** — produce findings, do not modify code or run
> exploits against any live system.

---

## 0. Role & Rules of Engagement

You are acting as an **application security red team** reviewing my own
codebase with my explicit authorization. Your job is to find weaknesses,
not to be reassuring. Be adversarial, specific, and evidence-based.

**Rules:**
- **Read-only.** Do not edit code, change configs, or call external systems.
  Propose remediations as diffs/snippets in the report only.
- **Cite evidence.** Every finding must reference concrete `path:line`
  locations (and config keys / topic names / endpoints where relevant).
  No hand-wavy "you should generally…" findings.
- **No fabrication.** If you cannot confirm something from the code, mark it
  as *Unconfirmed / needs manual check* rather than asserting it.
- **Ignore these directories entirely** (do not read, scan, or cite):
  - `documentation/archive/`
  - `documentation/planning/`
- Treat `docs/superpowers/BACKLOG.md` and
  `documentation/POSSIBLE_DEVELOPMENT.md` as **intent/roadmap context only**
  — useful for understanding direction, not as ground truth about what is
  built. Flag where planned-but-missing controls create current risk.

**Scope for this run:** `<<SCOPE: whole system | specific service | specific concern>>`

---

## 1. System Context (my stack)

- **Backend:** Java microservices on **Spring Boot 4**, built with **Maven**.
- **Inter-service comms:** **Kafka** (async/events) + **REST** (sync).
- **Frontend:** one microservice with a **React** frontend.
- **Runtime:** all services on **Kubernetes**.
- **Data:** **PostgreSQL 16** with **Apache AGE** (graph / openCypher) and
  **pgvector** (embeddings).
- **LLM:** one or more **local/external LLMs** reached through an internal
  **LiteLLM proxy** on the internal network.
- **Docs:** AsciiDoc under `documentation/`, PlantUML diagrams under
  `documentation/diagrams/`.

**Start by building your own model of the system before judging it:**
1. Read the `.adoc` docs under `documentation/` (excluding the ignored dirs)
   and the PUML diagrams under `documentation/diagrams/` to recover the
   *intended* architecture, trust boundaries, and data flows.
2. Then map the **actual** architecture from the code (services, topics,
   REST contracts, DB access, k8s manifests/Helm, config).
3. Explicitly call out **architecture drift**: where the implementation
   diverges from what the docs/diagrams claim.

---

## 2. Phase 1 — Architectural Red Team (do this first)

Evaluate the system *as a whole* before diving into any single service.
Produce a short architecture assessment covering:

### 2.1 Separation of Concerns
- Are responsibilities cleanly split across services, or are there "god"
  services / leaking domain boundaries?
- Is business logic bleeding into controllers, Kafka consumers, or the
  React layer? Is data-access mixed with domain logic?
- Does any service own data it shouldn't, or reach into another service's
  database directly (shared-DB anti-pattern)?

### 2.2 Loose Coupling & Contracts
- Kafka: are events versioned? Is there a schema/contract (Avro/JSON Schema/
  Protobuf)? What happens on schema evolution or a poison message?
- REST: are contracts explicit (OpenAPI)? Synchronous call chains that create
  hidden coupling or cascading-failure paths? Missing timeouts / retries /
  circuit breakers / bulkheads?
- Are services independently deployable, or is there temporal/build coupling?

### 2.3 Security Architecture Patterns
Assess against established patterns and name where each is present, partial,
or absent:
- **Defense in depth** — is security only at the edge, or layered?
- **Zero trust / no implicit intra-mesh trust** — is service-to-service
  traffic authenticated & authorized (mTLS, tokens), or "trusted because
  internal"? (The LiteLLM proxy and inter-service REST/Kafka are prime
  suspects here.)
- **Least privilege** — DB users, k8s RBAC/ServiceAccounts, Kafka ACLs,
  LLM/proxy credentials.
- **Secure by default / fail closed** — what happens when auth, the proxy,
  or the DB is unavailable?
- **Secrets management** — where do secrets live (k8s Secrets, env, mounted
  files, in images, in config repos)? Are they encrypted at rest / rotated?

### 2.4 Trust Boundaries & Threat Model
- Draw (in text or a Mermaid diagram) the trust boundaries and data flows:
  browser → frontend service → backend services → Kafka/REST → Postgres
  (AGE/pgvector) → LiteLLM proxy → LLM(s).
- Run a lightweight **STRIDE** pass per boundary/data flow (Spoofing,
  Tampering, Repudiation, Information disclosure, DoS, Elevation of
  privilege). Highlight the highest-leverage attack paths.

**Deliverable for Phase 1:** an architecture assessment with a ranked list of
*systemic* risks (the ones no single-file fix solves).

---

## 3. Phase 2 — Deep Application Review

Now go component-by-component. For each area, look for real, exploitable
issues, not style nits. Map findings to **OWASP Top 10 / API Top 10 / LLM
Top 10** where applicable.

### 3.1 AuthN / AuthZ
- User auth (frontend ↔ backend): token type, validation, expiry, refresh,
  storage (cookie flags / localStorage), session fixation.
- Service-to-service auth on REST and Kafka. Is anything "open because
  internal"?
- Authorization checks: missing/server-trusts-client checks, IDOR/BOLA on
  REST resources, vertical/horizontal privilege escalation.
- Spring Security config: filter chains, method security, default-permit gaps.

### 3.2 Injection & Input Validation
- SQL injection in JPA/JDBC/native queries.
- **AGE / openCypher injection** — string-built Cypher into `cypher()` calls.
- **pgvector** query construction and any user-influenced vector/metadata
  filters.
- Deserialization (Jackson polymorphic types, Kafka payload deserializers).
- Validation at every trust boundary (REST bodies, Kafka messages, query
  params, headers).

### 3.3 LLM-Specific (OWASP LLM Top 10)
- **Prompt injection** — untrusted data (user input, DB content, retrieved
  docs, tool output) flowing into prompts. Direct and indirect.
- **Insecure output handling** — LLM output used in queries, shell, HTML
  (XSS), or downstream calls without validation/encoding.
- **Sensitive data leakage** to the LLM/proxy: PII, secrets, internal data
  in prompts or logs. What does LiteLLM log/forward, and to which model
  backends?
- **SSRF / egress** via the proxy: can a user steer which model/endpoint is
  hit, or reach unintended internal services?
- Authn on the LiteLLM proxy itself; key handling; rate limiting / cost &
  model-DoS controls.

### 3.4 Kafka
- Topic-level ACLs and least privilege per service.
- Transport security (TLS) and authentication (SASL/mTLS).
- Poison-message / replay handling, idempotency, dead-letter strategy.
- PII/secret exposure in event payloads; retention vs. data-minimization.

### 3.5 REST APIs (OWASP API Top 10)
- BOLA/BFLA, mass assignment, excessive data exposure, missing rate limits.
- Error handling that leaks stack traces / internal details.
- CORS configuration; security headers.

### 3.6 React Frontend
- XSS (`dangerouslySetInnerHTML`, unsanitized rendering), CSP presence.
- CSRF posture given the auth model.
- Secrets / internal endpoints / debug flags shipped in the bundle.
- Dependency vulnerabilities (npm) and build provenance.

### 3.7 Data Layer (Postgres 16 / AGE / pgvector)
- DB users & roles — least privilege, separate roles per service, no shared
  superuser.
- Row-level security where multi-tenant; tenant isolation correctness.
- Encryption in transit (TLS) and at rest; backup exposure.
- Connection string / credential handling.

### 3.8 Kubernetes & Platform
- RBAC and ServiceAccount scoping; default-namespace sprawl.
- NetworkPolicies — is east-west traffic restricted, or flat? (Especially
  who may reach the LiteLLM proxy and Postgres.)
- Pod security (runAsNonRoot, readOnlyRootFS, dropped caps, no privileged).
- Secret handling in manifests/Helm; secrets in env vs. mounted; image
  registries and image scanning.
- Resource limits (DoS resilience); Actuator/management endpoint exposure
  in Spring Boot 4 (health/info/metrics/heapdump reachable externally?).

### 3.9 Supply Chain & Dependencies
- Maven & npm dependencies: known CVEs, unpinned/floating versions,
  abandoned libs. SBOM availability.
- Base images and their provenance.

### 3.10 Observability, Logging & Auditing
- Sensitive data in logs (tokens, prompts, PII, secrets).
- Audit trail for security-relevant actions; tamper resistance.
- Whether errors fail closed and are alertable.

---

## 4. Output Format

Produce a single report with:

1. **Executive summary** — top 5–10 risks, plainly stated, with overall
   risk posture.
2. **Architecture assessment** (Phase 1 output).
3. **Findings table**, sorted by severity. Each finding:

   | Field | Content |
   |---|---|
   | ID | RT-001 |
   | Title | short, specific |
   | Severity | Critical / High / Medium / Low / Info (justify the rating) |
   | Category | OWASP/API/LLM ref or "Architecture" |
   | Location | `path:line` (+ topic/endpoint/config key) |
   | Evidence | what in the code proves it |
   | Attack scenario | concrete, step-by-step exploit path |
   | Impact | what an attacker gains |
   | Remediation | specific fix (snippet/diff where useful) |
   | Confidence | Confirmed / Unconfirmed-needs-manual-check |

4. **Quick wins** vs **structural changes** — separate the cheap fixes from
   the ones needing design work.
5. **Coverage notes** — what you reviewed, what you couldn't, and what a
   human should verify manually.

---

## 5. How to run this

- If the codebase is large, do **Phase 1 first** and stop for my review,
  then run Phase 2 per service (`<<SCOPE>>` one service at a time) so each
  pass stays thorough rather than shallow-over-everything.
- Re-confirm at the start of each run that the ignored directories are
  excluded.
