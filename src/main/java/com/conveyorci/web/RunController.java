package com.conveyorci.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.RunResponse;
import com.conveyorci.web.ApiModels.RunSummary;

@RestController
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @GetMapping("/api/runs")
    public List<RunSummary> recent() {
        return runService.recent();
    }

    @PostMapping("/api/runs/{runId}/rerun")
    public ResponseEntity<RunResponse> rerun(@PathVariable Long runId) {
        RunResponse run = runService.rerun(runId);
        return ResponseEntity.created(URI.create("/api/runs/" + run.id())).body(run);
    }

    @GetMapping("/api/runs/{runId}")
    public RunResponse get(@PathVariable Long runId) {
        return runService.get(runId);
    }

    @PostMapping("/api/runs/{runId}/cancel")
    public RunResponse cancel(@PathVariable Long runId) {
        return runService.cancel(runId);
    }

    @GetMapping(value = "/api/jobs/{jobId}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(@PathVariable Long jobId) {
        return runService.logs(jobId);
    }
}
