package io.emcip.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Throwaway probe for P3.5a — proves ci-gate reports failure. Never merged. */
class CiGateProbeTest {

  @Test
  void deliberatelyFails() {
    assertThat(1).isEqualTo(2);
  }
}
