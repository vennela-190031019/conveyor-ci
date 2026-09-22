package com.conveyorci.pipeline;

import java.util.List;

/**
 * A job: an ordered list of steps that run in one container image.
 * {@code needs} lists the jobs that must succeed before this one can start.
 */
public record JobDefinition(
        String name,
        String image,
        List<String> needs,
        List<StepDefinition> steps,
        int retries,
        int timeoutMinutes) {

    public JobDefinition {
        needs = List.copyOf(needs);
        steps = List.copyOf(steps);
    }
}
