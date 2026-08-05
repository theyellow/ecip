package io.emcip.admin.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditTextTest {

    @Test
    void nullIn_nullOut() {
        assertThat(AuditText.sanitize(null)).isNull();
    }

    @Test
    void benignText_unchanged() {
        assertThat(AuditText.sanitize("alice@example.com")).isEqualTo("alice@example.com");
    }

    @Test
    void stripsControlAndFormatChars() {
        // U+202E (RLO, Cf), U+200B (zero-width space, Cf), U+0007 (bell, Cc)
        String dirty = "ad‮min​";
        assertThat(AuditText.sanitize(dirty)).isEqualTo("admin");
    }

    @Test
    void truncatesTo256() {
        String longName = "x".repeat(300);
        assertThat(AuditText.sanitize(longName)).hasSize(256);
    }
}
