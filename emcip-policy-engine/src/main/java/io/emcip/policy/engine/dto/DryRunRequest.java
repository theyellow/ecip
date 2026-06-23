package io.emcip.policy.engine.dto;

import io.emcip.policy.engine.condition.EvaluationContext;
import io.emcip.policy.engine.entity.PolicyRuleConfig;

public record DryRunRequest(PolicyRuleConfig rule, EvaluationContext context) {}
