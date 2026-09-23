package com.conveyorci.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.conveyorci.domain.Statuses.RunSource;
import com.conveyorci.domain.Statuses.RunStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** One execution of a pipeline for a specific commit. */
@Entity
@Table(name = "pipeline_run")
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Human-friendly, per-project sequence number (#1, #2, ...). */
    @Column(name = "run_number", nullable = false)
    private int runNumber;

    @Column(name = "pipeline_name", nullable = false, length = 100)
    private String pipelineName;

    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Column(nullable = false)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(name = "definition_yaml", nullable = false, columnDefinition = "TEXT")
    private String definitionYaml;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunSource source;

    /** Whether workers download the repository at {@code commitSha} into /workspace before step 1. */
    @Column(nullable = false)
    private boolean checkout;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stage ASC, id ASC")
    private List<Job> jobs = new ArrayList<>();

    protected PipelineRun() {
    }

    public PipelineRun(Project project, int runNumber, String pipelineName, String commitSha,
                       String branch, String definitionYaml, RunSource source, boolean checkout) {
        this.project = project;
        this.runNumber = runNumber;
        this.pipelineName = pipelineName;
        this.commitSha = commitSha;
        this.branch = branch;
        this.definitionYaml = definitionYaml;
        this.source = source;
        this.checkout = checkout;
        this.status = RunStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    /** A run that failed before any job could start, e.g. because its pipeline file is invalid. */
    public void failBeforeStart(String reason) {
        this.status = RunStatus.FAILED;
        this.failureReason = reason.length() > 500 ? reason.substring(0, 497) + "..." : reason;
        this.startedAt = this.createdAt;
        this.finishedAt = Instant.now();
    }

    public void addJob(Job job) {
        jobs.add(job);
        job.attachTo(this);
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public int getRunNumber() { return runNumber; }
    public String getPipelineName() { return pipelineName; }
    public String getCommitSha() { return commitSha; }
    public String getBranch() { return branch; }
    public RunStatus getStatus() { return status; }
    public String getDefinitionYaml() { return definitionYaml; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public List<Job> getJobs() { return jobs; }
    public RunSource getSource() { return source; }
    public boolean isCheckout() { return checkout; }
    public String getFailureReason() { return failureReason; }
}
