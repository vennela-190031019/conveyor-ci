package com.conveyorci.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conveyorci.engine.JobStore;
import com.conveyorci.engine.JobStore.WorkerInfo;

/** Registered workers and whether they've heartbeated in the last 15 seconds. */
@RestController
public class WorkerController {

    private final JobStore jobStore;

    public WorkerController(JobStore jobStore) {
        this.jobStore = jobStore;
    }

    @GetMapping("/api/workers")
    public List<WorkerInfo> list() {
        return jobStore.listWorkers();
    }
}
