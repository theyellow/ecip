# ADR-001: Technology Stack Selection

## Status

**Accepted**

Date: 2026-04-15

## Context

EMCIP (Enterprise Messenger Community Intelligence Platform) requires a technology stack that supports:
- High-throughput event processing from Telegram communities
- Reactive, non-blocking I/O for scalability
- Strong typing and maintainability for enterprise use
- Modern cloud-native deployment capabilities
- Cost-effective development and operations

## Decision

We will use the following technology stack:

### Core Platform
- **Java 21** (LTS) - Latest LTS with virtual threads for improved concurrency
- **Spring Boot 4** - Reactive programming with WebFlux, built-in observability
- **Maven** - Build tool with multi-module support

### Data & Messaging
- **Apache Kafka** - Event backbone for asynchronous communication
- **PostgreSQL 16** - Persistent storage with JSONB support
- **R2DBC** - Reactive database connectivity

### Infrastructure
- **Docker & Docker Compose** - Containerization and local development
- **GitHub Actions** - CI/CD pipeline

## Consequences

### Positive
- Virtual threads in Java 21 simplify concurrent programming
- Spring Boot 4 provides excellent reactive support and ecosystem
- Strong type safety with Java reduces runtime errors
- Large talent pool for Java/Spring development
- Mature tooling (IDEs, monitoring, profiling)

### Negative
- Higher memory footprint compared to Go/Rust alternatives
- More verbose than Python/JavaScript for rapid prototyping
- Learning curve for reactive programming (WebFlux)

## Alternatives Considered

| Alternative | Pros | Cons | Decision |
|-------------|------|------|----------|
| Go | Fast, low memory, compiled | Less mature ecosystem for reactive, smaller talent pool | Rejected |
| Node.js | Fast prototyping, large ecosystem | Single-threaded, callback complexity, type safety | Rejected |
| Python | Easy prototyping, ML libraries | Performance, GIL limitations | Rejected |
| Kotlin | Modern, concise, Java interop | Smaller talent pool, newer language | Rejected for now |

## References

- [OPEN_QUESTIONS.md](../OPEN_QUESTIONS.md) - Initial technology discussions
- [architecture.adoc](../architecture.adoc) - Detailed architecture documentation
- [US-1.1.1](../planning/phases/PHASE-1_USER_STORIES.md) - Maven parent POM setup

## Notes

This decision aligns with the project's goals for enterprise-grade reliability while maintaining developer productivity. Virtual threads specifically address our need for high concurrency with simpler code.
