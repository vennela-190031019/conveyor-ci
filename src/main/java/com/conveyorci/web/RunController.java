package com.conveyorci.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.RunResponse;

@RestController
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @GetMapping("/api/runs/{runId}")
    public RunResponse get(@PathVariable Long runId) {
        return runService.get(runId);
    }
}
