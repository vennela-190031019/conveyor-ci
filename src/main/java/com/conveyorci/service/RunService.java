package com.conveyorci.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conveyorci.domain.Job;
import com.conveyorci.domain.PipelineRun;
import com.conveyorci.domain.PipelineRunRepository;
import com.conveyorci.domain.Project;
import com.conveyorci.domain.ProjectRepository;
import com.conveyorci.domain.Step;
import com.conveyorci.pipeline.JobDefinition;
import com.conveyorci.pipeline.PipelineDefinition;
import com.conveyorci.pipeline.PipelineParser;
import com.conveyorci.pipeline.StepDefinition;
import com.conveyorci.web.ApiModels.RunResponse;
import com.conveyorci.web.ApiModels.RunSummary;
import com.conveyorci.web.ApiModels.TriggerRunRequest;

@Service
public class RunService {

    private final ProjectRepository projects;
    private final PipelineRunRepository runs;
    private final PipelineParser parser;

    public RunService(ProjectRepository projects, PipelineRunRepository runs, PipelineParser parser) {
        this.projects = projects;
        this.runs = runs;
        this.parser = parser;
    }

    /**
     * Validates the pipeline and persists a run with its full job/step graph in one transaction.
     * Jobs with no dependencies start QUEUED; everything else starts PENDING until the
     * scheduler (Phase 2) promotes it.
     */
    @Transactional
    public RunResponse trigger(Long projectId, TriggerRunRequest request) {
        // Parse before taking the lock: invalid pipelines should never block other triggers.
        PipelineDefinition definition = parser.parse(request.pipelineYaml());

        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("project " + projectId + " not found"));
        int runNumber = runs.findMaxRunNumber(projectId) + 1;

        PipelineRun run = new PipelineRun(project, runNumber, definition.name(),
                request.commitSha().toLowerCase(), request.branch().strip(), request.pipelineYaml());

        Map<String, Integer> stageByJob = definition.stageByJob();
        for (JobDefinition jobDef : definition.jobs()) {
            Job job = new Job(jobDef.name(), jobDef.image(), stageByJob.get(jobDef.name()),
                    jobDef.needs(), jobDef.retries() + 1, jobDef.timeoutMinutes());
            List<StepDefinition> steps = jobDef.steps();
            for (int i = 0; i < steps.size(); i++) {
                job.addStep(new Step(i + 1, steps.get(i).name(), steps.get(i).run()));
            }
            run.addJob(job);
        }

        PipelineRun saved = runs.saveAndFlush(run);
        return RunResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public RunResponse get(Long runId) {
        return runs.findById(runId).map(RunResponse::from)
                .orElseThrow(() -> new NotFoundException("run " + runId + " not found"));
    }

    @Transactional(readOnly = true)
    public List<RunSummary> listForProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new NotFoundException("project " + projectId + " not found");
        }
        return runs.findTop50ByProject_IdOrderByRunNumberDesc(projectId).stream()
                .map(RunSummary::from).toList();
    }
}
