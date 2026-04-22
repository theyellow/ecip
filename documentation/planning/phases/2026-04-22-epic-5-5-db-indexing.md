# Epic 5.5 — DB Indexing & Backup/Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add performance indexes to critical tables via Liquibase, and create tested pg_dump/pg_restore scripts for disaster recovery.

**Architecture:** Each module gets its own new Liquibase changeset for indexes (never modify existing changesets). Backup/restore scripts are parameterized shell scripts in `scripts/db/`. A Testcontainers integration test verifies the full backup→truncate→restore round-trip.

**Tech Stack:** PostgreSQL 16, Liquibase, pg_dump/pg_restore, Testcontainers, JUnit 5, Maven.

---

### Task 1: Indexes for conversation-context (messages table)

**Files:**
- Create: `emcip-conversation-context/src/main/resources/db/changelog/changes/004-add-performance-indexes.xml`
- Modify: `emcip-conversation-context/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Write the Liquibase changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="004" author="phase5">
        <!-- Timeline queries: messages by chat ordered by time -->
        <createIndex indexName="idx_messages_chat_created"
                     tableName="messages">
            <column name="chat_id"/>
            <column name="created_at"/>
        </createIndex>

        <!-- Thread lookup: messages within a thread -->
        <createIndex indexName="idx_messages_thread_id"
                     tableName="messages">
            <column name="thread_id"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Add include to master changelog**

In `emcip-conversation-context/src/main/resources/db/changelog/db.changelog-master.xml`, add:
```xml
<include file="changes/004-add-performance-indexes.xml"
         relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Verify migration applies cleanly**

```bash
mvn liquibase:update -pl emcip-conversation-context \
  -Dliquibase.url=jdbc:postgresql://localhost:14005/emcip \
  -Dliquibase.username=emcip \
  -Dliquibase.password=emcip
```

Expected: `UPDATE SUMMARY ... Liquibase command 'update' was executed successfully.`

- [ ] **Step 4: Commit**

```bash
git add emcip-conversation-context/src/main/resources/db/
git commit -m "feat(5.5): add performance indexes to messages table"
```

---

### Task 2: Indexes for policy-engine (policy_decisions table)

**Files:**
- Create: `emcip-policy-engine/src/main/resources/db/changelog/changes/003-add-performance-indexes.xml`
- Modify: `emcip-policy-engine/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Write the Liquibase changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="003" author="phase5">
        <!-- Rule reporting: decisions by intent type and time -->
        <createIndex indexName="idx_policy_decisions_intent_created"
                     tableName="policy_decisions">
            <column name="original_intent"/>
            <column name="timestamp"/>
        </createIndex>

        <!-- Decision lookup by source event -->
        <createIndex indexName="idx_policy_decisions_source_event"
                     tableName="policy_decisions">
            <column name="source_event_id"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Add include to master changelog**

In `emcip-policy-engine/src/main/resources/db/changelog/db.changelog-master.xml`, add:
```xml
<include file="changes/003-add-performance-indexes.xml"
         relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Verify migration**

```bash
mvn liquibase:update -pl emcip-policy-engine \
  -Dliquibase.url=jdbc:postgresql://localhost:14005/emcip \
  -Dliquibase.username=emcip \
  -Dliquibase.password=emcip
```

Expected: `Liquibase command 'update' was executed successfully.`

- [ ] **Step 4: Commit**

```bash
git add emcip-policy-engine/src/main/resources/db/
git commit -m "feat(5.5): add performance indexes to policy_decisions table"
```

---

### Task 3: Indexes for audit-service and moderation-service

**Files:**
- Create: `emcip-audit-service/src/main/resources/db/changelog/changes/001-add-performance-indexes.xml`
- Modify: `emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `emcip-moderation-service/src/main/resources/db/changelog/changes/002-add-performance-indexes.xml`
- Modify: `emcip-moderation-service/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Audit service changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="001-indexes" author="phase5">
        <!-- Audit log pagination by type and time -->
        <createIndex indexName="idx_audit_events_type_timestamp"
                     tableName="audit_events">
            <column name="event_type"/>
            <column name="timestamp"/>
        </createIndex>

        <!-- Correlation: find audit events by source event ID -->
        <createIndex indexName="idx_audit_events_source_id"
                     tableName="audit_events">
            <column name="source_event_id"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

Save to `emcip-audit-service/src/main/resources/db/changelog/changes/001-add-performance-indexes.xml`

- [ ] **Step 2: Moderation service changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="002-indexes" author="phase5">
        <!-- Active violations lookup by chat -->
        <createIndex indexName="idx_moderation_flags_chat_status"
                     tableName="moderation_flags">
            <column name="chat_id"/>
            <column name="status"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

Save to `emcip-moderation-service/src/main/resources/db/changelog/changes/002-add-performance-indexes.xml`

- [ ] **Step 3: Add includes to both master changelogs**

`emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml`:
```xml
<include file="changes/001-add-performance-indexes.xml"
         relativeToChangelogFile="true"/>
```

`emcip-moderation-service/src/main/resources/db/changelog/db.changelog-master.xml`:
```xml
<include file="changes/002-add-performance-indexes.xml"
         relativeToChangelogFile="true"/>
```

- [ ] **Step 4: Commit**

```bash
git add emcip-audit-service/src/main/resources/db/ emcip-moderation-service/src/main/resources/db/
git commit -m "feat(5.5): add performance indexes to audit_events and moderation_flags"
```

---

### Task 4: Backup script

**Files:**
- Create: `scripts/db/backup.sh`

- [ ] **Step 1: Create scripts directory and backup script**

```bash
mkdir -p scripts/db
```

```bash
#!/usr/bin/env bash
# scripts/db/backup.sh
# Usage: ./scripts/db/backup.sh [output_dir]
# Creates a timestamped PostgreSQL backup at output_dir/emcip_YYYYMMDD_HHMMSS.dump
# Defaults to ./backups/

set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-14005}"
DB_NAME="${DB_NAME:-emcip}"
DB_USER="${DB_USER:-emcip}"
PGPASSWORD="${PGPASSWORD:-emcip}"
OUTPUT_DIR="${1:-./backups}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
OUTPUT_FILE="${OUTPUT_DIR}/emcip_${TIMESTAMP}.dump"

mkdir -p "${OUTPUT_DIR}"

echo "[backup] Starting backup of ${DB_NAME} to ${OUTPUT_FILE}..."
PGPASSWORD="${PGPASSWORD}" pg_dump \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --username="${DB_USER}" \
  --format=custom \
  --compress=9 \
  --verbose \
  "${DB_NAME}" \
  --file="${OUTPUT_FILE}"

BACKUP_SIZE=$(du -sh "${OUTPUT_FILE}" | cut -f1)
echo "[backup] Done. File: ${OUTPUT_FILE} (${BACKUP_SIZE})"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x scripts/db/backup.sh
```

- [ ] **Step 3: Commit**

```bash
git add scripts/db/backup.sh
git commit -m "feat(5.5): add PostgreSQL backup script"
```

---

### Task 5: Restore script

**Files:**
- Create: `scripts/db/restore.sh`

- [ ] **Step 1: Create restore script**

```bash
#!/usr/bin/env bash
# scripts/db/restore.sh
# Usage: ./scripts/db/restore.sh <backup_file>
# Restores a pg_dump custom-format backup to the EMCIP database.
# WARNING: drops and recreates all tables — all existing data is lost.

set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <backup_file>"
  exit 1
fi

BACKUP_FILE="$1"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-14005}"
DB_NAME="${DB_NAME:-emcip}"
DB_USER="${DB_USER:-emcip}"
PGPASSWORD="${PGPASSWORD:-emcip}"

if [ ! -f "${BACKUP_FILE}" ]; then
  echo "[restore] Error: backup file not found: ${BACKUP_FILE}"
  exit 1
fi

echo "[restore] Restoring ${BACKUP_FILE} to ${DB_NAME}..."
PGPASSWORD="${PGPASSWORD}" pg_restore \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --username="${DB_USER}" \
  --dbname="${DB_NAME}" \
  --clean \
  --if-exists \
  --verbose \
  "${BACKUP_FILE}"

echo "[restore] Done."
```

- [ ] **Step 2: Make executable**

```bash
chmod +x scripts/db/restore.sh
```

- [ ] **Step 3: Smoke test against running database**

```bash
./scripts/db/backup.sh /tmp/ecip-test-backup
ls -la /tmp/ecip-test-backup/
```

Expected: a `.dump` file with non-zero size.

- [ ] **Step 4: Commit**

```bash
git add scripts/db/restore.sh
git commit -m "feat(5.5): add PostgreSQL restore script"
```

---

### Task 6: Backup/Restore integration test

**Files:**
- Create: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/BackupRestoreIT.java`

> This test verifies the round-trip: backup → truncate → restore → row counts match. It uses the real PostgreSQL container (port 14005) rather than Testcontainers, since Testcontainers can't run pg_dump on an external Docker container. It runs with the `integration-test` Maven lifecycle (profile `it`).

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.policy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for backup/restore scripts.
 * Requires a running PostgreSQL at localhost:14005.
 * Run with: mvn test -pl emcip-policy-engine -Dgroups=backup-restore
 */
@Tag("backup-restore")
@EnabledIfEnvironmentVariable(named = "ECIP_IT_ENABLED", matches = "true")
class BackupRestoreIT {

    private static final String JDBC_URL =
            "jdbc:postgresql://localhost:14005/emcip?user=emcip&password=emcip";

    @TempDir Path tempDir;

    @Test
    void backupAndRestorePreservesRowCounts() throws Exception {
        // Count rows before backup
        long policyRulesBefore = countRows("policy_rules");
        long policyDecisionsBefore = countRows("policy_decisions");

        // Run backup
        File backupFile = runBackup(tempDir);
        assertThat(backupFile).exists().isNotEmpty();

        // Truncate both tables
        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE policy_decisions, policy_rules CASCADE");
        }

        assertThat(countRows("policy_rules")).isZero();
        assertThat(countRows("policy_decisions")).isZero();

        // Run restore
        runRestore(backupFile);

        // Verify row counts match
        assertThat(countRows("policy_rules")).isEqualTo(policyRulesBefore);
        assertThat(countRows("policy_decisions")).isEqualTo(policyDecisionsBefore);
    }

    private long countRows(String table) throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private File runBackup(Path outputDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "bash", "scripts/db/backup.sh", outputDir.toString());
        pb.directory(new File(System.getProperty("user.dir") + "/.."));
        pb.environment().put("PGPASSWORD", "emcip");
        pb.inheritIO();
        int exit = pb.start().waitFor();
        assertThat(exit).as("backup.sh exit code").isEqualTo(0);
        return Files.list(outputDir)
                .filter(p -> p.toString().endsWith(".dump"))
                .findFirst()
                .map(Path::toFile)
                .orElseThrow(() -> new AssertionError("No dump file found"));
    }

    private void runRestore(File backupFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "bash", "scripts/db/restore.sh", backupFile.getAbsolutePath());
        pb.directory(new File(System.getProperty("user.dir") + "/.."));
        pb.environment().put("PGPASSWORD", "emcip");
        pb.inheritIO();
        int exit = pb.start().waitFor();
        assertThat(exit).as("restore.sh exit code").isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (requires running DB)**

```bash
ECIP_IT_ENABLED=true mvn test -pl emcip-policy-engine -Dgroups=backup-restore
```

Expected: FAIL — `backup.sh` and `restore.sh` exist but may fail if DB has no data. Seed a policy rule first if needed.

- [ ] **Step 3: Run with infrastructure available and verify pass**

```bash
docker compose up -d postgres
ECIP_IT_ENABLED=true mvn test -pl emcip-policy-engine -Dgroups=backup-restore
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add emcip-policy-engine/src/test/java/io/emcip/policy/engine/BackupRestoreIT.java
git commit -m "test(5.5): add backup/restore integration test"
```

---

### Task 7: Runbook documentation

**Files:**
- Create: `documentation/developer/backup-restore-runbook.md`

- [ ] **Step 1: Create the runbook**

```markdown
# EMCIP Database Backup & Restore Runbook

## Prerequisites

- `pg_dump` and `pg_restore` installed (`apt install postgresql-client` or `brew install libpq`)
- PostgreSQL running at `localhost:14005` (or set `DB_HOST`, `DB_PORT`)

## Backup

```bash
./scripts/db/backup.sh [output_dir]
# Default output_dir: ./backups/
# Creates: ./backups/emcip_YYYYMMDD_HHMMSS.dump
```

## Restore

```bash
./scripts/db/restore.sh <backup_file>
# Example: ./scripts/db/restore.sh backups/emcip_20260422_103000.dump
```

**WARNING:** Restore drops and recreates all tables. All existing data is lost.

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `14005` | PostgreSQL port |
| `DB_NAME` | `emcip` | Database name |
| `DB_USER` | `emcip` | Database user |
| `PGPASSWORD` | `emcip` | Database password |

## Backup format

Custom (`pg_dump --format=custom`), compressed. Restore requires `pg_restore` (not `psql`).

## Recovery verification

After restore, run the backup/restore test:

```bash
docker compose up -d postgres
ECIP_IT_ENABLED=true mvn test -pl emcip-policy-engine -Dgroups=backup-restore
```
```

- [ ] **Step 2: Commit**

```bash
git add documentation/developer/backup-restore-runbook.md
git commit -m "docs(5.5): add backup/restore runbook"
```

---

### Verification

```bash
# Verify all indexes were applied
docker compose up -d postgres
mvn liquibase:update -pl emcip-conversation-context,emcip-policy-engine \
  -Dliquibase.url=jdbc:postgresql://localhost:14005/emcip \
  -Dliquibase.username=emcip -Dliquibase.password=emcip

# Check indexes exist in DB
PGPASSWORD=emcip psql -h localhost -p 14005 -U emcip -c \
  "SELECT tablename, indexname FROM pg_indexes WHERE schemaname='public' AND indexname LIKE 'idx_%' ORDER BY tablename, indexname;"

# Backup smoke test
./scripts/db/backup.sh /tmp/ecip-verify
ls -lh /tmp/ecip-verify/
```
