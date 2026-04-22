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

@Tag("backup-restore")
@EnabledIfEnvironmentVariable(named = "ECIP_IT_ENABLED", matches = "true")
class BackupRestoreIT {

    private static final String JDBC_URL =
            "jdbc:postgresql://localhost:14005/emcip?user=emcip&password=emcip";

    @TempDir Path tempDir;

    @Test
    void backupAndRestorePreservesRowCounts() throws Exception {
        long policyRulesBefore = countRows("policy_rules");
        long policyDecisionsBefore = countRows("policy_decisions");

        File backupFile = runBackup(tempDir);
        assertThat(backupFile).exists().isNotEmpty();

        try (Connection conn = DriverManager.getConnection(JDBC_URL);
                Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE policy_decisions, policy_rules CASCADE");
        }

        assertThat(countRows("policy_rules")).isZero();

        runRestore(backupFile);

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
        ProcessBuilder pb =
                new ProcessBuilder("bash", "scripts/db/backup.sh", outputDir.toString());
        pb.directory(new File(System.getProperty("user.dir")).getParentFile());
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
        ProcessBuilder pb =
                new ProcessBuilder("bash", "scripts/db/restore.sh", backupFile.getAbsolutePath());
        pb.directory(new File(System.getProperty("user.dir")).getParentFile());
        pb.environment().put("PGPASSWORD", "emcip");
        pb.inheritIO();
        int exit = pb.start().waitFor();
        assertThat(exit).as("restore.sh exit code").isEqualTo(0);
    }
}
