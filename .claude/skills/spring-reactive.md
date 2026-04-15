---
name: spring-reactive
description: Spring WebFlux and R2DBC reactive programming patterns
triggers:
  - "webflux"
  - "r2dbc"
  - "reactive"
  - "mono"
  - "flux"
  - "non-blocking"
---

## Spring Reactive Programming

### Core Concepts

**Mono<T>** - 0 or 1 element (like Optional reactive)
**Flux<T>** - 0 to N elements (like Stream reactive)

### Repository Pattern with R2DBC

```java
@Repository
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {
    
    // Custom query with @Query
    @Query("SELECT * FROM users WHERE email = :email")
    Mono<User> findByEmail(String email);
    
    // Streaming results
    @Query("SELECT * FROM users WHERE created_at > :since")
    Flux<User> findRecentUsers(Instant since);
    
    // Count query
    @Query("SELECT COUNT(*) FROM users WHERE active = true")
    Mono<Long> countActiveUsers();
}
```

### Service Layer with Reactive Chaining

```java
@Service
public class UserService {
    private final UserRepository userRepository;
    private final AuditService auditService;
    
    public UserService(UserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }
    
    public Mono<User> createUser(CreateUserRequest request) {
        return validateRequest(request)
            .flatMap(this::checkEmailUnique)
            .map(this::toEntity)
            .flatMap(userRepository::save)
            .flatMap(user -> auditService.record("USER_CREATED", user)
                .thenReturn(user));
    }
    
    public Flux<User> findActiveUsers() {
        return userRepository.findAll()
            .filter(User::isActive)
            .take(100); // Limit for backpressure
    }
    
    private Mono<CreateUserRequest> validateRequest(CreateUserRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return Mono.error(new ValidationException("Email required"));
        }
        return Mono.just(request);
    }
    
    private Mono<CreateUserRequest> checkEmailUnique(CreateUserRequest request) {
        return userRepository.findByEmail(request.email())
            .flatMap(existing -> Mono.error(
                new DuplicateException("Email exists: " + request.email())))
            .switchIfEmpty(Mono.just(request));
    }
}
```

### WebFlux Router and Handler

```java
@Configuration
public class UserRouter {
    @Bean
    public RouterFunction<ServerResponse> userRoutes(UserHandler handler) {
        return RouterFunctions.route()
            .GET("/api/users", handler::listUsers)
            .GET("/api/users/{id}", handler::getUser)
            .POST("/api/users", handler::createUser)
            .PUT("/api/users/{id}", handler::updateUser)
            .DELETE("/api/users/{id}", handler::deleteUser)
            .build();
    }
}

@Component
public class UserHandler {
    private final UserService userService;
    
    public UserHandler(UserService userService) {
        this.userService = userService;
    }
    
    public Mono<ServerResponse> listUsers(ServerRequest request) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(userService.findActiveUsers(), User.class);
    }
    
    public Mono<ServerResponse> getUser(ServerRequest request) {
        UUID id = UUID.fromString(request.pathVariable("id"));
        return userService.findById(id)
            .flatMap(user -> ServerResponse.ok().bodyValue(user))
            .switchIfEmpty(ServerResponse.notFound().build());
    }
    
    public Mono<ServerResponse> createUser(ServerRequest request) {
        return request.bodyToMono(CreateUserRequest.class)
            .flatMap(userService::createUser)
            .flatMap(user -> ServerResponse.created(
                URI.create("/api/users/" + user.id())).bodyValue(user));
    }
}
```

### DatabaseClient for Custom SQL

```java
@Service
public class CustomQueryService {
    private final DatabaseClient dbClient;
    
    public CustomQueryService(DatabaseClient dbClient) {
        this.dbClient = dbClient;
    }
    
    public Mono<Map<String, Object>> executeCustomQuery(String sql) {
        return dbClient.sql(sql)
            .fetch()
            .first()
            .map(row -> {
                Map<String, Object> result = new HashMap<>();
                row.forEach((key, value) -> result.put(key, value));
                return result;
            });
    }
    
    public Flux<SummaryStats> getStats(Instant from, Instant to) {
        return dbClient.sql("""
            SELECT date_trunc('day', created_at) as day, count(*) as cnt
            FROM events WHERE created_at BETWEEN :from AND :to
            GROUP BY day ORDER BY day
            """)
            .bind("from", from)
            .bind("to", to)
            .map((row, metadata) -> new SummaryStats(
                row.get("day", LocalDateTime.class),
                row.get("cnt", Long.class)
            ))
            .all();
    }
}
```

### Reactive Transactions

```java
@Service
public class TransferService {
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    
    @Transactional
    public Mono<Void> transfer(UUID fromId, UUID toId, BigDecimal amount) {
        return accountRepo.findById(fromId)
            .flatMap(from -> {
                if (from.balance().compareTo(amount) < 0) {
                    return Mono.error(new InsufficientFundsException());
                }
                return accountRepo.findById(toId)
                    .flatMap(to -> {
                        Account newFrom = from.debit(amount);
                        Account newTo = to.credit(amount);
                        return accountRepo.save(newFrom)
                            .then(accountRepo.save(newTo))
                            .then(txRepo.save(new Transaction(fromId, toId, amount)))
                            .then();
                    });
            });
    }
}
```

### Error Handling

```java
@Configuration
public class ErrorHandler {
    @Bean
    public ErrorWebExceptionHandler errorHandler() {
        return new ErrorWebExceptionHandler() {
            @Override
            public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
                if (ex instanceof NotFoundException) {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return writeError(exchange, ex.getMessage());
                }
                if (ex instanceof ValidationException) {
                    exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                    return writeError(exchange, ex.getMessage());
                }
                exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                return writeError(exchange, "Internal error");
            }
            
            private Mono<Void> writeError(ServerWebExchange exchange, String message) {
                DataBuffer buffer = exchange.getResponse()
                    .bufferFactory()
                    .wrap(("{\"error\":\"" + message + "\"}").getBytes());
                return exchange.getResponse().writeWith(Mono.just(buffer));
            }
        };
    }
}
```

### Best Practices

1. **Never block** - No `.block()` in non-test code
2. **Chain operations** - Use `flatMap`, `map`, `then`, `zip`
3. **Handle errors** - `onErrorResume`, `onErrorReturn`
4. **Backpressure** - Use `take()`, `limitRate()` for large streams
5. **Timeouts** - Add `.timeout(Duration.ofSeconds(5))` for external calls
6. **Logging** - Use `.log()` or `doOnNext()`, `doOnError()` for debugging

### Testing Reactive Code

```java
@SpringBootTest
class UserServiceTest {
    @Autowired
    private UserService userService;
    
    @Test
    void testCreateUser() {
        StepVerifier.create(
            userService.createUser(new CreateUserRequest("test@example.com"))
        )
        .assertNext(user -> {
            assertThat(user.email()).isEqualTo("test@example.com");
            assertThat(user.id()).isNotNull();
        })
        .verifyComplete();
    }
    
    @Test
    void testDuplicateEmail() {
        StepVerifier.create(
            userService.createUser(new CreateUserRequest("existing@example.com"))
        )
        .expectError(DuplicateException.class)
        .verify();
    }
}
```
