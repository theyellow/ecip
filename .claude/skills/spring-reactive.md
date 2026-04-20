---
name: spring-reactive
description: Optional async programming with Mono/Flux for high-concurrency scenarios
model: claude-sonnet-4-20250514
triggers:
  - "reactive"
  - "mono"
  - "flux"
  - "@Async"
  - "WebClient"
  - "CompletableFuture"
  - "async"
---

# Optional: Async Programming with Mono/Flux

> **Status**: Optional skill - NOT used by default in EMCIP  
> **Default**: Project uses blocking JPA/Hibernate  
> **Use Case**: High-concurrency message processing (future scaling)

## When to Use This Skill

Use Mono/Flux only for:
- **WebClient** - Non-blocking HTTP calls to external services
- **@Async** methods - Background processing without blocking threads
- **Streaming** - Large data processing pipelines
- **CompletableFuture** integration - Bridging async and sync code

**Do NOT use for**: Database access (use JPA/Hibernate instead)

## Mono and Flux Basics

**Mono<T>** - 0 or 1 element (async Optional)
**Flux<T>** - 0 to N elements (async Stream)

### Creating Reactive Types

```java
// From value
Mono.just(user)
Flux.fromIterable(users)

// From Callable (async)
Mono.fromCallable(() -> blockingOperation())
    .subscribeOn(Schedulers.boundedElastic())

// Empty
Mono.empty()
Flux.empty()

// Error
Mono.error(new NotFoundException())

// From Future
Mono.fromFuture(completableFuture)
```

### WebClient (Non-Blocking HTTP)

```java
@Service
@RequiredArgsConstructor
public class ExternalApiService {
    private final WebClient webClient;
    
    public Mono<ExternalResponse> fetchData(String id) {
        return webClient.get()
            .uri("https://api.example.com/data/{id}", id)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, 
                response -> Mono.error(new NotFoundException()))
            .onStatus(HttpStatusCode::is5xxServerError,
                response -> Mono.error(new ExternalServiceException()))
            .bodyToMono(ExternalResponse.class)
            .timeout(Duration.ofSeconds(5))
            .retryWhen(Retry.backoff(3, Duration.ofMillis(500)))
            .doOnError(e -> log.error("Failed to fetch data for {}", id, e));
    }
    
    // Blocking bridge (use in blocking services)
    public ExternalResponse fetchDataBlocking(String id) {
        return fetchData(id).block(Duration.ofSeconds(10));
    }
}
```

### @Async Processing

```java
@Service
@RequiredArgsConstructor
public class AsyncProcessor {
    private final WebClient webClient;
    
    @Async
    public CompletableFuture<Void> processMessagesAsync(List<Message> messages) {
        return Flux.fromIterable(messages)
            .flatMap(this::enrichMessage)
            .flatMap(this::sendToExternalApi)
            .collectList()
            .then()
            .toFuture();
    }
    
    private Mono<Message> enrichMessage(Message msg) {
        return webClient.get()
            .uri("/api/enrich/{id}", msg.getId())
            .retrieve()
            .bodyToMono(EnrichmentData.class)
            .map(data -> msg.withEnrichment(data))
            .onErrorResume(e -> {
                log.warn("Failed to enrich {}", msg.getId(), e);
                return Mono.just(msg); // Continue without enrichment
            });
    }
    
    private Mono<Message> sendToExternalApi(Message msg) {
        return webClient.post()
            .uri("/api/messages")
            .bodyValue(msg)
            .retrieve()
            .toBodilessEntity()
            .thenReturn(msg)
            .doOnSuccess(m -> log.info("Sent message {}", m.getId()));
    }
}
```

### Reactive Chaining Patterns

```java
public Mono<ProcessResult> processEvent(Event event) {
    return validateEvent(event)           // Mono<Event>
        .flatMap(this::enrichData)         // Mono<EnrichedEvent>
        .flatMap(this::callExternalApi)    // Mono<ApiResponse>
        .map(this::transformToResult)     // Mono<ProcessResult>
        .flatMap(this::saveResult)        // Mono<ProcessResult>
        .doOnSuccess(r -> log.info("Processed: {}", r.getId()))
        .doOnError(e -> log.error("Failed: {}", event.getId(), e))
        .onErrorResume(e -> {
            // Return default/empty result on error
            return Mono.just(ProcessResult.failed(event.getId(), e.getMessage()));
        });
}

// Parallel processing
public Flux<ProcessResult> processBatch(List<Event> events) {
    return Flux.fromIterable(events)
        .flatMap(this::processEvent, 10)  // Max 10 parallel
        .collectList();
}
```

### Error Handling

```java
// Retry with backoff
mono.retryWhen(Retry.backoff(3, Duration.ofMillis(100))
    .maxBackoff(Duration.ofSeconds(5)))

// Fallback value
mono.onErrorReturn(defaultValue)

// Fallback from another mono
mono.onErrorResume(e -> fallbackMono)

// Specific error handling
mono.onErrorResume(ExternalServiceException.class, 
    e -> Mono.just(cachedValue))

// Timeout
mono.timeout(Duration.ofSeconds(5))
    .onErrorResume(TimeoutException.class, 
        e -> Mono.error(new ServiceUnavailableException()))
```

### Testing Reactive Code

```java
@ExtendWith(MockitoExtension.class)
class AsyncProcessorTest {
    
    @Mock
    private WebClient webClient;
    
    @InjectMocks
    private AsyncProcessor processor;
    
    @Test
    void shouldProcessMessage() {
        // given
        Message msg = createMessage();
        
        // when
        CompletableFuture<Void> future = processor.processMessagesAsync(List.of(msg));
        
        // then
        StepVerifier.create(Mono.fromFuture(future))
            .verifyComplete();
    }
    
    @Test
    void shouldHandleError() {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), any()))
            .thenThrow(new RuntimeException("Network error"));
        
        StepVerifier.create(processor.enrichData(createMessage()))
            .expectNextCount(1) // Fallback to original message
            .verifyComplete();
    }
}
```

### Integration with Blocking JPA

```java
@Service
@RequiredArgsConstructor
public class HybridService {
    private final MessageRepository messageRepository; // JPA (blocking)
    private final WebClient webClient;                  // WebClient (non-blocking)
    
    // Blocking service method
    @Transactional
    public void processMessage(UUID id) {
        Message msg = messageRepository.findById(id)
            .orElseThrow(() -> new NotFoundException());
        
        // Async external call
        ExternalData data = fetchExternalData(msg.getExternalId()).block();
        
        msg.enrichWith(data);
        messageRepository.save(msg);
    }
    
    // Reactive external call
    private Mono<ExternalData> fetchExternalData(String externalId) {
        return webClient.get()
            .uri("/api/external/{id}", externalId)
            .retrieve()
            .bodyToMono(ExternalData.class)
            .timeout(Duration.ofSeconds(5));
    }
}
```

## Best Practices

1. **Bridge sparingly** - Use `.block()` only at service boundaries
2. **Schedulers matter** - Use `Schedulers.boundedElastic()` for blocking calls
3. **Always timeout** - Prevent hanging with `.timeout()`
4. **Handle errors** - Never let errors go unhandled
5. **Log reactive chains** - Use `.doOnNext()`, `.doOnError()` for debugging

## Migration Path from Blocking

If you need to migrate to fully reactive later:
1. Start with WebClient for external calls (current use case)
2. Use @Async for background processing
3. Consider R2DBC only if you prove you need 10k+ concurrent connections
4. Keep JPA for complex domain logic

## Related Skills

- `spring-boot-jpa` - Default blocking database access
- `kafka-messaging` - Async message processing (already event-driven)
- `project-topology` - Service architecture
