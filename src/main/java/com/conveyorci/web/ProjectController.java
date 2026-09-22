package com.conveyorci.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conveyorci.service.ProjectService;
import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.CreateProjectRequest;
import com.conveyorci.web.ApiModels.ProjectResponse;
import com.conveyorci.web.ApiModels.RunResponse;
import com.conveyorci.web.ApiModels.RunSummary;
import com.conveyorci.web.ApiModels.TriggerRunRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final RunService runService;

    public ProjectController(ProjectService projectService, RunService runService) {
        this.projectService = projectService;
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse created = projectService.create(request);
        return ResponseEntity.created(URI.create("/api/projects/" + created.id())).body(created);
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.list();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable Long projectId) {
        return projectService.get(projectId);
    }

    @PostMapping("/{projectId}/runs")
    public ResponseEntity<RunResponse> trigger(@PathVariable Long projectId,
                                               @Valid @RequestBody TriggerRunRequest request) {
        RunResponse run = runService.trigger(projectId, request);
        return ResponseEntity.created(URI.create("/api/runs/" + run.id())).body(run);
    }

    @GetMapping("/{projectId}/runs")
    public List<RunSummary> listRuns(@PathVariable Long projectId) {
        return runService.listForProject(projectId);
    }
}
