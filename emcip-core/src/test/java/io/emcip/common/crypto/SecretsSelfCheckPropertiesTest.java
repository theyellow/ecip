package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import org.junit.jupiter.api.Test;

class SecretsSelfCheckPropertiesTest {

    @Test
    void defaultsToWarnWhenUnset() {
        assertThat(new SecretsSelfCheckProperties(null).selfCheck()).isEqualTo(SelfCheckMode.WARN);
    }

    @Test
    void keepsAnExplicitMode() {
        assertThat(new SecretsSelfCheckProperties(SelfCheckMode.FAIL).selfCheck())
                .isEqualTo(SelfCheckMode.FAIL);
        assertThat(new SecretsSelfCheckProperties(SelfCheckMode.OFF).selfCheck())
                .isEqualTo(SelfCheckMode.OFF);
    }
}
