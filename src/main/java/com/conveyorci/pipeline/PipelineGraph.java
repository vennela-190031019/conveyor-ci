package com.conveyorci.pipeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-graph checks and stage computation for a set of jobs.
 *
 * <p>Edges point from a job to the jobs it {@code needs}. The graph must be a DAG:
 * no self-dependencies, no references to unknown jobs, no cycles.
 */
public final class PipelineGraph {

    private enum Color { WHITE, GRAY, BLACK }

    private PipelineGraph() {
    }

    /**
     * Validates the graph and returns jobs grouped into stages, where a job's stage is
     * the length of the longest dependency chain beneath it. Order within a stage follows
     * declaration order, so output is deterministic.
     */
    public static List<List<String>> computeStages(List<JobDefinition> jobs) {
        Map<String, JobDefinition> byName = new LinkedHashMap<>();
        for (JobDefinition job : jobs) {
            byName.put(job.name(), job);
        }

        List<String> errors = new ArrayList<>();
        for (JobDefinition job : jobs) {
            for (String need : job.needs()) {
                if (need.equals(job.name())) {
                    errors.add("job '" + job.name() + "' cannot depend on itself");
                } else if (!byName.containsKey(need)) {
                    errors.add("job '" + job.name() + "' needs unknown job '" + need + "'");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new PipelineValidationException(errors);
        }

        List<String> cycle = findCycle(byName);
        if (cycle != null) {
            throw new PipelineValidationException(
                    List.of("dependency cycle detected: " + String.join(" -> ", cycle)));
        }

        Map<String, Integer> depth = new HashMap<>();
        int maxDepth = 0;
        for (String name : byName.keySet()) {
            maxDepth = Math.max(maxDepth, depthOf(name, byName, depth));
        }

        List<List<String>> stages = new ArrayList<>();
        for (int i = 0; i <= maxDepth; i++) {
            stages.add(new ArrayList<>());
        }
        for (String name : byName.keySet()) {
            stages.get(depth.get(name)).add(name);
        }
        return stages;
    }

    /** Iterative three-color DFS. Returns the cycle as a path (first node repeated at the end), or null. */
    private static List<String> findCycle(Map<String, JobDefinition> byName) {
        Map<String, Color> color = new HashMap<>();
        byName.keySet().forEach(n -> color.put(n, Color.WHITE));

        for (String start : byName.keySet()) {
            if (color.get(start) != Color.WHITE) {
                continue;
            }
            // Each frame: node plus index of the next dependency to explore.
            Deque<String> path = new ArrayDeque<>();
            Deque<int[]> nextIndex = new ArrayDeque<>();
            path.push(start);
            nextIndex.push(new int[] {0});
            color.put(start, Color.GRAY);

            while (!path.isEmpty()) {
                String node = path.peek();
                int[] idx = nextIndex.peek();
                List<String> needs = byName.get(node).needs();

                if (idx[0] < needs.size()) {
                    String next = needs.get(idx[0]++);
                    Color c = color.get(next);
                    if (c == Color.GRAY) {
                        return extractCycle(path, next);
                    }
                    if (c == Color.WHITE) {
                        color.put(next, Color.GRAY);
                        path.push(next);
                        nextIndex.push(new int[] {0});
                    }
                } else {
                    color.put(node, Color.BLACK);
                    path.pop();
                    nextIndex.pop();
                }
            }
        }
        return null;
    }

    private static List<String> extractCycle(Deque<String> stack, String repeated) {
        // stack iterates top (most recent) to bottom; reverse into root-to-leaf order.
        List<String> rootToLeaf = new ArrayList<>(stack);
        java.util.Collections.reverse(rootToLeaf);
        List<String> cycle = new ArrayList<>(rootToLeaf.subList(rootToLeaf.indexOf(repeated), rootToLeaf.size()));
        cycle.add(repeated);
        return cycle;
    }

    private static int depthOf(String name, Map<String, JobDefinition> byName, Map<String, Integer> memo) {
        Integer cached = memo.get(name);
        if (cached != null) {
            return cached;
        }
        int d = 0;
        for (String need : byName.get(name).needs()) {
            d = Math.max(d, depthOf(need, byName, memo) + 1);
        }
        memo.put(name, d);
        return d;
    }
}
