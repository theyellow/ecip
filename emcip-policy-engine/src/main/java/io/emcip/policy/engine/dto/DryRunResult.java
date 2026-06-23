package io.emcip.policy.engine.dto;

import java.util.List;

public record DryRunResult(
        boolean matched,
        int matchedGroupIndex,
        String action,
        List<GroupResult> groupResults) {}
