# ADR-009: admin-api DataSource Bean for Secrets Self-Check

**Status:** Accepted  
**Created:** 2026-09-21  
**Authors:** Benjamin Marstaller  
**Related:** PR #246, P2.0-F1 (Secrets Self-Check System)

---

## Context

### The Problem

The `admin-api` service entered **CrashLoopBackOff** on September 13, 2026, with **8,700+ restarts** over 7+ days. The entire Admin UI was completely non-functional.

**Error Message:**
```
Parameter 0 of method secretColumnScanner in io.emcip.common.crypto.SecretsSelfCheckConfig 
required a bean of type 'javax.sql.DataSource' that could not be found.
```

### Background

The eCIP platform recently completed implementation of the **Secrets Self-Check System** (P2.0-F1), which:
- Scans database tables for encrypted secret columns
- Validates decryption works correctly at boot
- Exposes health gauges and metrics
- Runs with configurable modes (warn/fail/off)

This system is implemented in `emcip-core` and imported by services that store secrets:
- `admin-api` (Telegram credentials)
- `knowledge-engine` (Vendor API keys)
- `llm-orchestrator` (LLM provider credentials)

### The Technical Conflict

The `admin-api` uses a **hybrid database access pattern**:
- **R2DBC** (reactive) for application data access
- **JDBC** (blocking) for Liquibase schema migrations

When Spring Boot detects both `spring.r2dbc.url` and `spring.datasource.url` configuration, it:
1. Prioritizes R2DBC for reactive operations
2. Creates a `DatabaseClient` bean for R2DBC
3. **Does NOT create a traditional `javax.sql.DataSource` bean**
4. Only uses JDBC configuration for Liquibase migrations (internal to `SpringLiquibase`)

The `SecretsSelfCheckConfig` explicitly requires `DataSource` for JDBC-based table scanning:
```java
@Bean
public SecretColumnScanner secretColumnScanner(DataSource dataSource, SecretCipher cipher) {
    return new SecretColumnScanner(dataSource, cipher);
}
```

This worked in other services (`knowledge-engine`, `llm-orchestrator`) because they use **JDBC-only** configuration (`SPRING_DATASOURCE_URL`), which automatically creates the `DataSource` bean.

---

## Decision

### Solution: Expose DataSource as a Spring Bean

**1. Modify `LiquibaseConfig.java` to expose `DataSource` as a bean:**

```java
@Configuration
public class LiquibaseConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    /**
     * DataSource bean for Liquibase migrations and any other components that require traditional
     * JDBC (e.g., SecretsSelfCheckConfig's secret column scanning).
     *
     * <p>admin-api uses R2DBC for reactive CRUD operations, but certain operations (schema
     * migrations, secret scanning) require blocking JDBC. This bean is intentionally minimal and
     * not used for application data access.
     */
    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public SpringLiquibase liquibase() {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource());
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        return liquibase;
    }
}
```

**Key Changes:**
- Extract `DriverManagerDataSource` creation into its own `@Bean` method
- Refactor `liquibase()` to use the bean via `dataSource()`
- Add documentation explaining the dual purpose

**2. Add JDBC configuration to Helm values:**

```yaml
# helm/emcip/values.yaml
services:
  adminApi:
    env:
      SPRING_R2DBC_URL: "r2dbc:postgresql://emcip-postgres:5432/emcip"
      SPRING_LIQUIBASE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"
      SPRING_DATASOURCE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"  # ← Added
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: "org.postgresql.Driver"  # ← Added
```

**Why This Approach:**
- Minimal code change (~17 lines)
- No breaking changes to existing functionality
- Does not affect R2DBC data access layer
- Reuses existing Liquibase configuration
- Aligns admin-api with other services' configuration patterns

---

## Consequences

### Positive

1. **Restores Admin UI functionality**
   - Service starts successfully
   - No more CrashLoopBackOff
   - Operators can access credentials, tenants, flags, users, rules

2. **Enables Secrets Self-Check System**
   - Secret column scanning works as designed
   - Health gauges exposed
   - Encryption validation at boot

3. **Maintains separation of concerns**
   - R2DBC continues to handle application data access
   - JDBC only used for migrations and secret scanning
   - Clear documentation of dual-purpose DataSource

### Negative

1. **Additional bean in container**
   - Minimal overhead (single DataSource bean)
   - Not used for general data access

2. **Configuration complexity**
   - Requires both R2DBC and JDBC URLs
   - May confuse future maintainers without documentation

### Mitigations

- Documentation in code (Javadoc on `dataSource()` bean)
- This ADR explains the rationale
- Helm values clearly labeled

---

## Verification

### Startup Logs (Before Fix)
```
Exception encountered during context initialization:
No qualifying bean of type 'javax.sql.DataSource' available
APPLICATION FAILED TO START
```

### Startup Logs (After Fix)
```
Started AdminApiApplication in 58.306 seconds
SECRET SELF-CHECK  mode=warn
  telegram_accounts.api_hash            1 encrypted,    0 plaintext  [OK]
  telegram_accounts.session_string      0 encrypted,    0 plaintext  [UNVERIFIED]
```

### Pod Status
```
emcip-admin-api-55f8d488fc-wqthg  1/1  Running  17h
```

---

## Alternatives Considered

### 1. Use R2DBC `DatabaseClient` Instead

**Proposal:** Modify `SecretsSelfCheckConfig` to use `DatabaseClient` (R2DBC) instead of `DataSource`.

**Rejected Because:**
- Secret scanning requires JDBC metadata operations (`DatabaseMetaData`, `ResultSet`)
- R2DBC's reactive API is overkill for one-time boot scanning
- Would require significant refactoring of `SecretColumnScanner`
- Other services already use JDBC for this purpose

### 2. Make `SecretsSelfCheckConfig` Conditional

**Proposal:** Add `@ConditionalOnBean(DataSource.class)` to skip secret scanning if no DataSource.

**Rejected Because:**
- Silently disables critical functionality
- Admin API stores secrets and needs the check
- Would mask configuration problems

### 3. Remove Secrets Self-Check from admin-api

**Proposal:** Don't import `SecretsSelfCheckConfig` in admin-api's `CryptoConfig`.

**Rejected Because:**
- admin-api stores encrypted Telegram credentials
- Needs the same validation as other services
- P2.0-F1 explicitly includes admin-api

### 4. Switch admin-api to JDBC-Only

**Proposal:** Replace R2DBC with JDBC throughout admin-api.

**Rejected Because:**
- Would require massive refactoring
- R2DBC provides real benefits for reactive webflux
- Other services work fine with hybrid approach

---

## References

- **PR:** https://github.com/theyellow/ecip/pull/246
- **Issue:** admin-api CrashLoopBackOff (since 2026-09-13)
- **Feature:** P2.0-F1 Secrets Self-Check System
- **Related ADR:** ADR-004 (R2DBC Reactive Stack)

---

## Lessons Learned

1. **Test hybrid R2DBC/JDBC configurations thoroughly**
   - Spring Boot's auto-configuration priorities are not obvious
   - Bean creation order matters

2. **Document shared beans clearly**
   - The `DataSource` bean serves multiple purposes
   - Future maintainers need to understand why it exists

3. **Consider all consumers of shared components**
   - `SecretsSelfCheckConfig` is in `emcip-core` (shared library)
   - Services importing it must meet its dependencies

4. **Configuration consistency across services**
   - Services with similar patterns should use similar configuration
   - Reduces cognitive load and deployment surprises
