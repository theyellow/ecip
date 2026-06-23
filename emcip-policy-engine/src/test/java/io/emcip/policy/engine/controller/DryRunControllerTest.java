package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.condition.ConditionType;
import io.emcip.policy.engine.condition.EvaluationContext;
import io.emcip.policy.engine.dto.ConditionResult;
import io.emcip.policy.engine.dto.DryRunRequest;
import io.emcip.policy.engine.dto.DryRunResult;
import io.emcip.policy.engine.dto.GroupResult;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.service.DryRunService;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DryRunControllerTest {

    @Mock private DryRunService dryRunService;
    @InjectMocks private DryRunController controller;

    @Test
    void matched_resultIsReturned() {
        DryRunResult result =
                new DryRunResult(
                        true,
                        0,
                        "BLOCK",
                        List.of(
                                new GroupResult(
                                        0,
                                        true,
                                        List.of(
                                                new ConditionResult(
                                                        ConditionType.MIN_THREAD_LENGTH,
                                                        true,
                                                        "5 >= 3")))));
        when(dryRunService.evaluate(any(), any())).thenReturn(result);

        DryRunRequest req =
                new DryRunRequest(
                        new PolicyRuleConfig(),
                        new EvaluationContext(
                                "SPAM", 0.9, "en", 5, 120, 45, 2, 0, 90, ZonedDateTime.now()));

        StepVerifier.create(controller.dryRun(req))
                .assertNext(
                        r -> {
                            assertThat(r.matched()).isTrue();
                            assertThat(r.matchedGroupIndex()).isEqualTo(0);
                            assertThat(r.action()).isEqualTo("BLOCK");
                            assertThat(r.groupResults()).hasSize(1);
                        })
                .verifyComplete();
    }

    @Test
    void notMatched_resultHasMatchedFalse() {
        DryRunResult result =
                new DryRunResult(
                        false,
                        -1,
                        "BLOCK",
                        List.of(
                                new GroupResult(
                                        0,
                                        false,
                                        List.of(
                                                new ConditionResult(
                                                        ConditionType.FLAGGED_COUNT,
                                                        false,
                                                        "0 >= 3 in last 30d")))));
        when(dryRunService.evaluate(any(), any())).thenReturn(result);

        DryRunRequest req =
                new DryRunRequest(
                        new PolicyRuleConfig(),
                        new EvaluationContext(
                                "SPAM", 0.9, "en", 0, 0, 0, 0, 0, 90, ZonedDateTime.now()));

        StepVerifier.create(controller.dryRun(req))
                .assertNext(r -> assertThat(r.matched()).isFalse())
                .verifyComplete();
    }
}
