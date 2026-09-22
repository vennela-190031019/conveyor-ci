package com.conveyorci.pipeline;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated pipeline. {@code stages} groups jobs by DAG depth: every job in stage N
 * depends only on jobs in stages &lt; N, so all jobs within a stage can run in parallel.
 */
public record PipelineDefinition(String name, List<JobDefinition> jobs, List<List<String>> stages) {

    public PipelineDefinition {
        jobs = List.copyOf(jobs);
        stages = stages.stream().map(List::copyOf).toList();
    }

    /** Map of job name to the stage index it belongs to. */
    public Map<String, Integer> stageByJob() {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < stages.size(); i++) {
            for (String job : stages.get(i)) {
                result.put(job, i);
            }
        }
        return result;
    }
}
