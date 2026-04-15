---
name: liquibase-migrations
description: Database schema versioning with Liquibase in Spring Boot
triggers:
  - "liquibase"
  - "migration"
  - "changelog"
  - "changeset"
  - "db.changelog"
---

## Liquibase Database Migrations

### Configuration

```yaml
# application.yml
spring:
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.xml
    default-schema: public
    liquibase-schema: public
```

### Master Changelog Structure

```xml
<!-- db/changelog/db.changelog-master.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="1" author="developer">
        <tagDatabase tag="1.0.0"/>
    </changeSet>
    
    <include file="changesets/001-create-users-table.xml" 
             relativeToChangelogFile="true"/>
    <include file="changesets/002-create-messages-table.xml" 
             relativeToChangelogFile="true"/>
    <include file="changesets/003-create-indexes.xml" 
             relativeToChangelogFile="true"/>
    
</databaseChangeLog>
```

### Table Creation Changeset

```xml
<!-- changesets/001-create-users-table.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="001-create-users-table" author="developer">
        <preConditions onFail="MARK_RAN">
            <not>
                <tableExists tableName="users"/>
            </not>
        </preConditions>
        
        <createTable tableName="users">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="telegram_user_id" type="BIGINT">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="username" type="VARCHAR(255)"/>
            <column name="first_name" type="VARCHAR(255)"/>
            <column name="last_name" type="VARCHAR(255)"/>
            <column name="is_bot" type="BOOLEAN" defaultValueBoolean="false"/>
            <column name="language_code" type="VARCHAR(10)"/>
            <column name="metadata" type="JSONB"/>
            <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
        </createTable>
        
        <createIndex indexName="idx_users_telegram_id" tableName="users">
            <column name="telegram_user_id"/>
        </createIndex>
        
        <createIndex indexName="idx_users_username" tableName="users">
            <column name="username"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

### Table with Foreign Keys

```xml
<!-- changesets/002-create-messages-table.xml -->
<changeSet id="002-create-messages-table" author="developer">
    <preConditions onFail="MARK_RAN">
        <not>
            <tableExists tableName="messages"/>
        </not>
    </preConditions>
    
    <createTable tableName="messages">
        <column name="id" type="UUID">
            <constraints primaryKey="true" nullable="false"/>
        </column>
        <column name="telegram_message_id" type="BIGINT">
            <constraints nullable="false"/>
        </column>
        <column name="chat_id" type="BIGINT">
            <constraints nullable="false"/>
        </column>
        <column name="user_id" type="UUID">
            <constraints nullable="false"/>
        </column>
        <column name="reply_to_message_id" type="UUID"/>
        <column name="message_type" type="VARCHAR(50)">
            <constraints nullable="false"/>
        </column>
        <column name="content" type="TEXT"/>
        <column name="entities" type="JSONB"/>
        <column name="metadata" type="JSONB"/>
        <column name="sent_at" type="TIMESTAMPTZ">
            <constraints nullable="false"/>
        </column>
        <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
            <constraints nullable="false"/>
        </column>
    </createTable>
    
    <!-- Foreign Key -->
    <addForeignKeyConstraint 
        baseTableName="messages" 
        baseColumnNames="user_id"
        constraintName="fk_messages_user"
        referencedTableName="users"
        referencedColumnNames="id"
        onDelete="CASCADE"/>
    
    <!-- Self-referencing FK for replies -->
    <addForeignKeyConstraint
        baseTableName="messages"
        baseColumnNames="reply_to_message_id"
        constraintName="fk_messages_reply"
        referencedTableName="messages"
        referencedColumnNames="id"
        onDelete="SET NULL"/>
    
    <!-- Indexes -->
    <createIndex indexName="idx_messages_chat_sent" tableName="messages">
        <column name="chat_id"/>
        <column name="sent_at"/>
    </createIndex>
    
    <createIndex indexName="idx_messages_user" tableName="messages">
        <column name="user_id"/>
    </createIndex>
    
    <sql>
        CREATE INDEX idx_messages_content_gin ON messages 
        USING GIN (to_tsvector('english', content));
    </sql>
</changeSet>
```

### JSONB Column Patterns

```xml
<!-- For flexible metadata storage -->
<changeSet id="003-create-intent-classifications" author="developer">
    <createTable tableName="intent_classifications">
        <column name="id" type="UUID">
            <constraints primaryKey="true"/>
        </column>
        <column name="message_id" type="UUID">
            <constraints nullable="false"/>
        </column>
        <column name="intent" type="VARCHAR(100)">
            <constraints nullable="false"/>
        </column>
        <column name="confidence" type="DECIMAL(3,2)">
            <constraints nullable="false"/>
        </column>
        <!-- JSONB for flexible parameters -->
        <column name="parameters" type="JSONB">
            <constraints nullable="false"/>
        </column>
        <!-- JSONB for rule matches -->
        <column name="rule_matches" type="JSONB"/>
        <column name="classified_at" type="TIMESTAMPTZ" 
                defaultValueComputed="NOW()"/>
    </createTable>
    
    <!-- GIN index for JSONB queries -->
    <sql>
        CREATE INDEX idx_intent_params_gin ON intent_classifications 
        USING GIN (parameters);
    </sql>
</changeSet>
```

### Adding Columns

```xml
<changeSet id="004-add-columns" author="developer">
    <preConditions onFail="MARK_RAN">
        <not>
            <columnExists tableName="users" columnName="is_active"/>
        </not>
    </preConditions>
    
    <addColumn tableName="users">
        <column name="is_active" type="BOOLEAN" defaultValueBoolean="true">
            <constraints nullable="false"/>
        </column>
    </addColumn>
    
    <addColumn tableName="users">
        <column name="last_activity_at" type="TIMESTAMPTZ"/>
    </addColumn>
</changeSet>
```

### Modifying Data

```xml
<changeSet id="005-seed-data" author="developer">
    <insert tableName="policies">
        <column name="id" value="550e8400-e29b-41d4-a716-446655440000"/>
        <column name="name" value="default-policy"/>
        <column name="description" value="Default moderation policy"/>
        <column name="conditions" valueClobFile="default-policy-conditions.json"/>
        <column name="actions" valueClobFile="default-policy-actions.json"/>
        <column name="priority" valueNumeric="100"/>
        <column name="is_active" valueBoolean="true"/>
        <column name="created_at" valueComputed="NOW()"/>
    </insert>
</changeSet>
```

### Rolling Back

```xml
<changeSet id="006-create-audit-table" author="developer">
    <createTable tableName="audit_events">
        <column name="id" type="UUID">
            <constraints primaryKey="true"/>
        </column>
        <column name="event_type" type="VARCHAR(100)"/>
        <column name="payload" type="JSONB"/>
        <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()"/>
    </createTable>
    
    <rollback>
        <dropTable tableName="audit_events"/>
    </rollback>
</changeSet>
```

### Best Practices

1. **Always use preConditions** - Prevent failures if already applied
2. **Tag releases** - Use `<tagDatabase>` for version markers
3. **One changeSet per file** - Easier to track
4. **Descriptive IDs** - `001-create-users-table` not just `1`
5. **Include author** - Track who made changes
6. **Test rollbacks** - Ensure they work
7. **Use JSONB for PostgreSQL** - Flexible schema evolution

### Maven Commands

```bash
# Run migrations
mvn liquibase:update

# Check status
mvn liquibase:status

# Generate rollback script
mvn liquibase:rollback -Dliquibase.rollbackTag=1.0.0

# Validate changelog
mvn liquibase:validate

# Generate SQL without running
mvn liquibase:updateSQL
```

### YAML Format Alternative

```yaml
# db/changelog/changes/001-create-users.yaml
databaseChangeLog:
  - changeSet:
      id: 001-create-users-table
      author: developer
      preConditions:
        - onFail: MARK_RAN
        - not:
            tableExists:
              tableName: users
      changes:
        - createTable:
            tableName: users
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: telegram_user_id
                  type: BIGINT
                  constraints:
                    nullable: false
                    unique: true
```
