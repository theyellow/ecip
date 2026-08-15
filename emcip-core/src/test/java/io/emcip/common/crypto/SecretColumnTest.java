package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretColumnTest {

    @Test
    void acceptsPlainLowerSnakeCaseIdentifiers() {
        SecretColumn c = new SecretColumn("telegram_accounts", "api_hash", "id");
        assertThat(c.location()).isEqualTo("telegram_accounts.api_hash");
    }

    // These identifiers are concatenated into SQL. The constructor is the only thing
    // standing between a descriptor and an injection, so it must reject anything that
    // is not a bare lower-snake-case identifier.
    @Test
    void rejectsIdentifiersThatCouldBreakOutOfTheQuery() {
        assertThatThrownBy(() -> new SecretColumn("users; drop table users", "api_key", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretColumn("users", "api_key\" , 1 --", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretColumn("Users", "api_key", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretColumn("users", "", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretColumn(null, "api_key", "id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectionMessageNamesTheOffendingFieldWithoutEchoingUnboundedInput() {
        assertThatThrownBy(() -> new SecretColumn("users", "bad name", "id"))
                .hasMessageContaining("column");
    }
}
