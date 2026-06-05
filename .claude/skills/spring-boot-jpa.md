---
name: spring-boot-jpa
description: JPA entities, repositories, and services with Spring Boot
triggers:
  - "jpa"
  - "entity"
  - "repository"
  - "@Entity"
  - "@Table"
  - "JpaRepository"
  - "@Id"
  - "@GeneratedValue"
  - "liquibase"
---

# Spring Boot JPA Development

## Entity Creation

### Required Annotations
```java
@Entity
@Table(name = "table_name")  // Explicit table name
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MyEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false)  // For required fields
    private String requiredField;
    
    @Column(nullable = true)   // For optional fields
    private String optionalField;
    
    @CreationTimestamp
    private Instant createdAt;
    
    @UpdateTimestamp
    private Instant updatedAt;
    
    @Version
    private Long version;
}
```

## Repository Creation

### Standard Pattern
```java
@Repository
public interface MyEntityRepository extends JpaRepository<MyEntity, UUID> {
    
    // Query methods
    Optional<MyEntity> findBySomeField(String someField);
    
    List<MyEntity> findByStatusOrderByCreatedAtDesc(Status status);
    
    boolean existsByUniqueField(String uniqueField);
    
    // Custom JPQL
    @Query("SELECT e FROM MyEntity e WHERE e.status = :status")
    List<MyEntity> findByStatusCustom(@Param("status") Status status);
}
```

## Service Creation

### Required Pattern
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class MyEntityService {
    
    private final MyEntityRepository repository;
    
    @Transactional
    public MyEntity createEntity(CreateRequest request) {
        log.info("Creating entity with field: {}", request.getField());
        
        MyEntity entity = MyEntity.builder()
            .field(request.getField())
            .build();
        
        return repository.save(entity);
    }
    
    @Transactional(readOnly = true)
    public Optional<MyEntity> findById(UUID id) {
        return repository.findById(id);
    }
}
```

## Critical Rules

1. **NEVER** write manual getters/setters/equals/hashCode - use Lombok
2. **ALWAYS** use `@Column(nullable = false)` for required fields
3. **ALWAYS** include `@Version` for optimistic locking
4. **ALWAYS** use constructor injection with `@RequiredArgsConstructor`
5. **ALWAYS** use `@Slf4j` for logging (never LoggerFactory)
6. **NEVER** use `var` in lambdas for JPA queries

## After adding a constructor parameter to any service

Some services in this project use a manual constructor instead of `@RequiredArgsConstructor`
(e.g. when `@Qualifier` is needed for multiple beans of the same type). When you add a field
to such a service, the compiler won't catch test breakage until CI runs — because unit tests
often construct the service directly with `new ServiceName(...)`.

**Always do this after adding a constructor parameter:**

```bash
grep -r "new <ServiceName>(" src/test/
```

Then add the matching mock and update the constructor call in every test that directly
instantiates the service. Forgetting this is silent locally but breaks CI.

## Testing

```java
@DataJpaTest
@Testcontainers
@Import(TestcontainersInitializer.class)
class MyEntityRepositoryTest {
    
    @Autowired
    private MyEntityRepository repository;
    
    @Test
    void shouldSaveAndRetrieveEntity() {
        // given
        MyEntity entity = MyEntity.builder()
            .field("value")
            .build();
        
        // when
        MyEntity saved = repository.save(entity);
        
        // then
        assertThat(saved.getId()).isNotNull();
    }
}
```

## Liquibase Migration

Create file: `src/main/resources/db/changelog/changes/XXX-create-my-entity-table.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">
    
    <changeSet id="XXX-create-my-entity-table" author="developer">
        <createTable tableName="my_entities">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="field" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="BIGINT">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>
</databaseChangeLog>
```

## Common Commands

```bash
# Check migration status
mvn liquibase:status -pl <module>

# Apply migrations
mvn liquibase:update -pl <module>

# Run tests
mvn test -pl <module>
```

## Related Files
- `documentation/planning/phases/` - User stories
- `docs/superpowers/BACKLOG.md` - Open work items
