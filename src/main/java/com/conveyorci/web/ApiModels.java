package com.conveyorci.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.conveyorci.domain.Job;
import com.conveyorci.domain.PipelineRun;
import com.conveyorci.domain.Project;
import com.conveyorci.domain.Statuses.JobStatus;
import com.conveyorci.domain.Statuses.RunStatus;
import com.conveyorci.domain.Statuses.StepStatus;
import com.conveyorci.domain.Step;
import com.conveyorci.pipeline.PipelineDefinition;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request/response DTOs. Entities never leave the service layer. */
public final class ApiModels {

    private ApiModels() {
    }

    private static final String SLUG = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";

    public record CreateProjectRequest(
            @NotBlank @Pattern(regexp = SLUG, message = "must be 1-100 chars: letters, digits, '.', '_' or '-'")
            String owner,
            @NotBlank @Pattern(regexp = SLUG, message = "must be 1-100 chars: letters, digits, '.', '_' or '-'")
            String name,
            @Size(max = 255) String defaultBranch) {
    }

    public record ProjectResponse(Long id, String owner, String name, String defaultBranch, Instant createdAt) {
        public static ProjectResponse from(Project p) {
            return new ProjectResponse(p.getId(), p.getOwner(), p.getName(), p.getDefaultBranch(), p.getCreatedAt());
        }
    }

    public record TriggerRunRequest(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{7,40}", message = "must be a 7-40 character hex commit SHA")
            String commitSha,
            @NotBlank @Size(max = 255) String branch,
            @NotBlank String pipelineYaml) {
    }

    public record StepResponse(int position, String name, String command, StepStatus status, Integer exitCode) {
        static StepResponse from(Step s) {
            return new StepResponse(s.getPosition(), s.getName(), s.getCommand(), s.getStatus(), s.getExitCode());
        }
    }

    public record JobResponse(Long id, String name, String image, int stage, JobStatus status, List<String> needs,
                              int attempt, int maxAttempts, int timeoutMinutes, Instant startedAt,
                              Instant finishedAt, List<StepResponse> steps) {
        static JobResponse from(Job j) {
            return new JobResponse(j.getId(), j.getName(), j.getImage(), j.getStage(), j.getStatus(),
                    List.copyOf(j.getDependsOn()), j.getAttempt(), j.getMaxAttempts(), j.getTimeoutMinutes(),
                    j.getStartedAt(), j.getFinishedAt(), j.getSteps().stream().map(StepResponse::from).toList());
        }
    }

    public record RunResponse(Long id, Long projectId, int runNumber, String pipelineName, String commitSha,
                              String branch, RunStatus status, Instant createdAt, Instant startedAt,
                              Instant finishedAt, List<List<String>> stages, List<JobResponse> jobs) {
        public static RunResponse from(PipelineRun r) {
            List<JobResponse> jobs = r.getJobs().stream().map(JobResponse::from).toList();
            return new RunResponse(r.getId(), r.getProject().getId(), r.getRunNumber(), r.getPipelineName(),
                    r.getCommitSha(), r.getBranch(), r.getStatus(), r.getCreatedAt(), r.getStartedAt(),
                    r.getFinishedAt(), stagesOf(jobs), jobs);
        }

        private static List<List<String>> stagesOf(List<JobResponse> jobs) {
            List<List<String>> stages = new ArrayList<>();
            for (JobResponse job : jobs) {
                while (stages.size() <= job.stage()) {
                    stages.add(new ArrayList<>());
                }
                stages.get(job.stage()).add(job.name());
            }
            return stages;
        }
    }

    public record RunSummary(Long id, int runNumber, String pipelineName, String commitSha, String branch,
                             RunStatus status, Instant createdAt, Instant finishedAt) {
        public static RunSummary from(PipelineRun r) {
            return new RunSummary(r.getId(), r.getRunNumber(), r.getPipelineName(), r.getCommitSha(),
                    r.getBranch(), r.getStatus(), r.getCreatedAt(), r.getFinishedAt());
        }
    }

    public record ValidationResponse(boolean valid, String pipelineName, List<List<String>> stages,
                                     List<String> errors) {
        public static ValidationResponse ok(PipelineDefinition d) {
            return new ValidationResponse(true, d.name(), d.stages(), List.of());
        }

        public static ValidationResponse invalid(List<String> errors) {
            return new ValidationResponse(false, null, List.of(), errors);
        }
    }
}
