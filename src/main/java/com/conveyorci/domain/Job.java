package com.conveyorci.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.conveyorci.domain.Statuses.JobStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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

/** A node in a run's DAG. Created PENDING (has dependencies) or QUEUED (ready to run). */
@Entity
@Table(name = "job")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private PipelineRun run;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private String image;

    /** DAG depth; jobs in the same stage can run in parallel. */
    @Column(nullable = false)
    private int stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "timeout_minutes", nullable = false)
    private int timeoutMinutes;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "job_dependency", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "depends_on", length = 64)
    private List<String> dependsOn = new ArrayList<>();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<Step> steps = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Job() {
    }

    public Job(String name, String image, int stage, List<String> dependsOn, int maxAttempts, int timeoutMinutes) {
        this.name = name;
        this.image = image;
        this.stage = stage;
        this.dependsOn = new ArrayList<>(dependsOn);
        this.maxAttempts = maxAttempts;
        this.timeoutMinutes = timeoutMinutes;
        this.attempt = 0;
        this.status = dependsOn.isEmpty() ? JobStatus.QUEUED : JobStatus.PENDING;
        this.createdAt = Instant.now();
    }

    void attachTo(PipelineRun run) {
        this.run = run;
    }

    public void addStep(Step step) {
        steps.add(step);
        step.attachTo(this);
    }

    public Long getId() { return id; }
    public PipelineRun getRun() { return run; }
    public String getName() { return name; }
    public String getImage() { return image; }
    public int getStage() { return stage; }
    public JobStatus getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getTimeoutMinutes() { return timeoutMinutes; }
    public List<String> getDependsOn() { return dependsOn; }
    public List<Step> getSteps() { return steps; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
