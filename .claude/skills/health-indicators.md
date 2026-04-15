---
name: health-indicators
description: Custom Spring Boot Actuator health indicators
triggers:
  - "health"
  - "HealthIndicator"
  - "actuator"
  - "/actuator/health"
  - "liveness"
  - "readiness"
---

## Custom Health Indicators

### Database Health Indicator

```java
@Component
public class DatabaseHealthIndicator implements HealthIndicator {
    
    private final DatabaseClient databaseClient;
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    public DatabaseHealthIndicator(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }
    
    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        
        try {
            return databaseClient.sql("SELECT 1")
                .fetch()
                .rowsUpdated()
                .map(count -> Health.up()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("connection", "established")
                    .withDetail("responseTimeMs", System.currentTimeMillis() - start)
                    .build())
                .onErrorResume(e -> {
                    log.warn("Database health check failed: {}", e.getMessage());
                    return Mono.just(Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("error", e.getMessage())
                        .build());
                })
                .block(Duration.ofSeconds(3));
        } catch (Exception e) {
            return Health.down()
                .withDetail("database", "PostgreSQL")
                .withDetail("error", "Health check timed out: " + e.getMessage())
                .build();
        }
    }
}
```

### Kafka Health Indicator

```java
@Component
public class KafkaHealthIndicator implements HealthIndicator {
    
    private final KafkaAdmin kafkaAdmin;
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }
    
    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        
        try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Cluster cluster = client.describeCluster().clusterId()
                .get(5, TimeUnit.SECONDS);
            
            Collection<Node> nodes = client.describeCluster().nodes()
                .get(5, TimeUnit.SECONDS);
            
            return Health.up()
                .withDetail("kafka", "connected")
                .withDetail("brokers", nodes.size())
                .withDetail("responseTimeMs", System.currentTimeMillis() - start)
                .build();
                
        } catch (Exception e) {
            log.warn("Kafka health check failed: {}", e.getMessage());
            return Health.down()
                .withDetail("kafka", "disconnected")
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

### External Service Health Indicator

```java
@Component
public class LlmProviderHealthIndicator implements HealthIndicator {
    
    private final WebClient webClient;
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    public LlmProviderHealthIndicator(
            @Value("${llm.provider.health-url}") String healthUrl,
            WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
            .baseUrl(healthUrl)
            .build();
    }
    
    @Override
    public Health health() {
        return webClient.get()
            .retrieve()
            .toBodilessEntity()
            .timeout(Duration.ofSeconds(5))
            .map(response -> {
                if (response.getStatusCode().is2xxSuccessful()) {
                    return Health.up()
                        .withDetail("provider", "available")
                        .withDetail("status", response.getStatusCode())
                        .build();
                }
                return Health.down()
                    .withDetail("provider", "unhealthy")
                    .withDetail("status", response.getStatusCode())
                    .build();
            })
            .onErrorResume(e -> {
                log.warn("LLM provider health check failed: {}", e.getMessage());
                return Mono.just(Health.down()
                    .withDetail("provider", "unavailable")
                    .withDetail("error", e.getMessage())
                    .build());
            })
            .block();
    }
}
```

### Composite Health Indicator

```java
@Component
public class InfrastructureHealthIndicator implements HealthIndicator {
    
    private final DatabaseHealthIndicator dbHealth;
    private final KafkaHealthIndicator kafkaHealth;
    
    public InfrastructureHealthIndicator(
            DatabaseHealthIndicator dbHealth,
            KafkaHealthIndicator kafkaHealth) {
        this.dbHealth = dbHealth;
        this.kafkaHealth = kafkaHealth;
    }
    
    @Override
    public Health health() {
        Health db = dbHealth.health();
        Health kafka = kafkaHealth.health();
        
        Health.Builder builder = (db.getStatus().equals(Status.UP) && 
                                   kafka.getStatus().equals(Status.UP))
            ? Health.up() : Health.down();
        
        return builder
            .withDetail("database", db.getDetails())
            .withDetail("kafka", kafka.getDetails())
            .build();
    }
}
```

### Configuration

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
      base-path: /actuator
  endpoint:
    health:
      show-details: always
      show-components: always
      probes:
        enabled: true  # Kubernetes probes
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

### Kubernetes Probes

```java
// Liveness probe - is the application running?
// Readiness probe - is the application ready to accept traffic?

// These are automatically provided by Spring Boot Actuator:
// GET /actuator/health/liveness
// GET /actuator/health/readiness
```

### Custom Status Aggregator

```java
@Component
public class CustomStatusAggregator implements StatusAggregator {
    
    @Override
    public Status getAggregateStatus(Set<Status> statuses) {
        if (statuses.contains(Status.DOWN)) {
            return Status.DOWN;
        }
        if (statuses.contains(Status.OUT_OF_SERVICE)) {
            return Status.OUT_OF_SERVICE;
        }
        if (statuses.contains(Status.UNKNOWN)) {
            return Status.UNKNOWN;
        }
        return Status.UP;
    }
}
```

### Testing Health Indicators

```java
@SpringBootTest
@AutoConfigureMockMvc
class HealthIndicatorTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private DatabaseClient databaseClient;
    
    @Test
    void whenDatabaseIsUp_thenHealthIsUp() throws Exception {
        when(databaseClient.sql(anyString())).thenReturn(mockFetch());
        
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.db.status").value("UP"));
    }
    
    @Test
    void whenDatabaseIsDown_thenHealthIsDown() throws Exception {
        when(databaseClient.sql(anyString()))
            .thenThrow(new DataAccessException("Connection refused") {});
        
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
```

### Health Response Examples

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "connection": "established",
        "responseTimeMs": 12
      }
    },
    "kafka": {
      "status": "UP",
      "details": {
        "kafka": "connected",
        "brokers": 1,
        "responseTimeMs": 45
      }
    },
    "livenessState": {
      "status": "UP"
    },
    "readinessState": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

### Docker Health Check

```dockerfile
# Dockerfile
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1
```

### Best Practices

1. **Set timeouts** - Always use `.block(Duration)` or `.timeout()`
2. **Don't cache** - Health checks should reflect current state
3. **Add details** - Include response times, version info
4. **Log failures** - But not on every check to avoid log spam
5. **Fast checks** - Health endpoints are called frequently
6. **Separate concerns** - Different indicators for different dependencies
