package com.conveyorci.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Turns the raw object tree produced by a YAML parser (maps, lists, scalars) into a
 * {@link PipelineDefinition}. Validation is strict and collects every error in one pass,
 * so a user fixing a pipeline sees all problems at once instead of one per attempt.
 *
 * <p>Deliberately free of YAML/Spring dependencies so it can be unit-tested with plain maps.
 */
public final class PipelineDefinitionReader {

    public static final int MAX_JOBS = 50;
    public static final int MAX_STEPS_PER_JOB = 100;
    public static final int MAX_RETRIES = 5;
    public static final int MAX_TIMEOUT_MINUTES = 360;
    public static final int DEFAULT_TIMEOUT_MINUTES = 30;
    public static final String DEFAULT_PIPELINE_NAME = "pipeline";

    private static final Pattern JOB_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Set<String> ROOT_KEYS = Set.of("name", "jobs");
    private static final Set<String> JOB_KEYS = Set.of("image", "needs", "steps", "retries", "timeout_minutes");
    private static final Set<String> STEP_KEYS = Set.of("name", "run");

    public PipelineDefinition read(Object root) {
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new PipelineValidationException(List.of("pipeline must be a YAML mapping with a 'jobs' key"));
        }

        List<String> errors = new ArrayList<>();
        checkUnknownKeys(rootMap, ROOT_KEYS, "pipeline", errors);

        String name = DEFAULT_PIPELINE_NAME;
        Object nameNode = rootMap.get("name");
        if (nameNode != null) {
            if (nameNode instanceof String s && !s.isBlank() && s.length() <= 100) {
                name = s.strip();
            } else {
                errors.add("pipeline 'name' must be a non-empty string of at most 100 characters");
            }
        }

        List<JobDefinition> jobs = new ArrayList<>();
        Object jobsNode = rootMap.get("jobs");
        if (!(jobsNode instanceof Map<?, ?> jobsMap) || jobsMap.isEmpty()) {
            errors.add("'jobs' must be a non-empty mapping of job name to job definition");
        } else {
            if (jobsMap.size() > MAX_JOBS) {
                errors.add("pipeline has " + jobsMap.size() + " jobs; the maximum is " + MAX_JOBS);
            }
            for (Map.Entry<?, ?> entry : jobsMap.entrySet()) {
                JobDefinition job = readJob(String.valueOf(entry.getKey()), entry.getValue(), errors);
                if (job != null) {
                    jobs.add(job);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new PipelineValidationException(errors);
        }

        List<List<String>> stages = PipelineGraph.computeStages(jobs);
        return new PipelineDefinition(name, jobs, stages);
    }

    private JobDefinition readJob(String jobName, Object node, List<String> errors) {
        String where = "job '" + jobName + "'";
        int errorsBefore = errors.size();

        if (!JOB_NAME.matcher(jobName).matches()) {
            errors.add(where + ": name must be 1-64 characters of letters, digits, '-' or '_', "
                    + "starting with a letter or digit");
        }
        if (!(node instanceof Map<?, ?> job)) {
            errors.add(where + ": must be a mapping with 'image' and 'steps'");
            return null;
        }
        checkUnknownKeys(job, JOB_KEYS, where, errors);

        String image = null;
        Object imageNode = job.get("image");
        if (imageNode instanceof String s && !s.isBlank()) {
            image = s.strip();
        } else {
            errors.add(where + ": 'image' is required and must be a non-empty string");
        }

        List<String> needs = readNeeds(job.get("needs"), where, errors);
        List<StepDefinition> steps = readSteps(job.get("steps"), where, errors);
        int retries = readInt(job.get("retries"), 0, 0, MAX_RETRIES, where + ": 'retries'", errors);
        int timeout = readInt(job.get("timeout_minutes"), DEFAULT_TIMEOUT_MINUTES, 1, MAX_TIMEOUT_MINUTES,
                where + ": 'timeout_minutes'", errors);

        if (errors.size() > errorsBefore) {
            return null;
        }
        return new JobDefinition(jobName, image, needs, steps, retries, timeout);
    }

    private List<String> readNeeds(Object node, String where, List<String> errors) {
        if (node == null) {
            return List.of();
        }
        List<?> raw = node instanceof List<?> list ? list : List.of(node);
        Set<String> needs = new LinkedHashSet<>();
        for (Object item : raw) {
            if (!(item instanceof String s) || s.isBlank()) {
                errors.add(where + ": 'needs' must be a job name or a list of job names");
                return List.of();
            }
            if (!needs.add(s.strip())) {
                errors.add(where + ": 'needs' lists '" + s.strip() + "' more than once");
            }
        }
        return new ArrayList<>(needs);
    }

    private List<StepDefinition> readSteps(Object node, String where, List<String> errors) {
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            errors.add(where + ": 'steps' is required and must be a non-empty list");
            return List.of();
        }
        if (list.size() > MAX_STEPS_PER_JOB) {
            errors.add(where + ": has " + list.size() + " steps; the maximum is " + MAX_STEPS_PER_JOB);
        }
        List<StepDefinition> steps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String stepWhere = where + " step " + (i + 1);
            if (!(list.get(i) instanceof Map<?, ?> step)) {
                errors.add(stepWhere + ": must be a mapping with a 'run' command");
                continue;
            }
            checkUnknownKeys(step, STEP_KEYS, stepWhere, errors);

            Object run = step.get("run");
            if (!(run instanceof String command) || command.isBlank()) {
                errors.add(stepWhere + ": 'run' is required and must be a non-empty string");
                continue;
            }
            String stepName = "Step " + (i + 1);
            Object nameNode = step.get("name");
            if (nameNode != null) {
                if (nameNode instanceof String s && !s.isBlank()) {
                    stepName = s.strip();
                } else {
                    errors.add(stepWhere + ": 'name' must be a non-empty string");
                }
            }
            steps.add(new StepDefinition(stepName, command));
        }
        return steps;
    }

    private static int readInt(Object node, int defaultValue, int min, int max, String what, List<String> errors) {
        if (node == null) {
            return defaultValue;
        }
        // YAML parsers produce Integer or Long; reject booleans, floats and strings.
        if (node instanceof Integer || node instanceof Long) {
            long value = ((Number) node).longValue();
            if (value >= min && value <= max) {
                return (int) value;
            }
        }
        errors.add(what + " must be a whole number between " + min + " and " + max);
        return defaultValue;
    }

    private static void checkUnknownKeys(Map<?, ?> map, Set<String> allowed, String where, List<String> errors) {
        Set<String> unknown = new TreeSet<>();
        for (Object key : map.keySet()) {
            String k = String.valueOf(key);
            if (!allowed.contains(k)) {
                unknown.add(k);
            }
        }
        if (!unknown.isEmpty()) {
            errors.add(where + ": unknown key(s) " + unknown + "; allowed keys are " + new TreeSet<>(allowed));
        }
    }
}
