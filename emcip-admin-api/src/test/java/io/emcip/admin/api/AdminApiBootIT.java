package io.emcip.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.crypto.ColumnResult;
import io.emcip.common.crypto.SecretsSelfCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the real admin-api application context against a real PostgreSQL.
 *
 * <p>admin-api had no {@code @SpringBootTest} at all (SELFCHECK-F2), so a wiring defect could only
 * surface at deploy time — and one did: P3.7's self-check required a {@code javax.sql.DataSource}
 * that this R2DBC service never exposed, and admin-api crash-looped on the cluster for a week
 * (fixed in PR #246, see ADR-009). This test fails on exactly that defect.
 *
 * <p>It also proves the self-check actually scanned both registered {@code telegram_accounts}
 * columns against the Liquibase-built schema, so a wrong table or column name in {@code
 * CryptoConfig} fails here instead of at runtime.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiBootIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("emcip")
                    .withUsername("emcip")
                    .withPassword("emcip");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + POSTGRES.getHost()
                                + ":"
                                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                                + "/"
                                + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        // Skips 015-create-service-roles: it grants on tables owned by other services' changelogs,
        // which do not exist in a database holding only admin-api's schema.
        registry.add("spring.liquibase.contexts", () -> "test");
        // Any valid 32-byte key: this test is about wiring, not about decrypting real data.
        registry.add("emcip.secret-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        // JwtService and ServiceTokenAuthenticationFilter both refuse the shipped defaults at
        // startup, as they should.
        registry.add("admin.jwt.secret", () -> "admin-api-boot-it-jwt-secret-0123456789abcdef");
        registry.add("admin.service-token", () -> "admin-api-boot-it-service-token");
    }

    @Autowired SecretsSelfCheck selfCheck;

    @Test
    void contextStartsAndSelfCheckScansBothTelegramColumns() {
        // An empty table has no encrypted row to prove the key against, so UNVERIFIED is the
        // correct outcome. The point is that a result exists per column: a failed scan (bad
        // DataSource, wrong identifier) leaves lastResults empty in warn mode.
        assertThat(selfCheck.lastResults())
                .extracting(r -> r.column().table() + "." + r.column().column())
                .containsExactlyInAnyOrder(
                        "telegram_accounts.api_hash", "telegram_accounts.session_string");
        assertThat(selfCheck.lastResults())
                .extracting(ColumnResult::outcome)
                .containsOnly(ColumnResult.Outcome.UNVERIFIED);
    }
}
