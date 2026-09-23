package com.conveyorci.domain;

import java.time.Instant;

import com.conveyorci.domain.Statuses.StepStatus;

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
import jakarta.persistence.Table;

/** One shell command within a job. */
@Entity
@Table(name = "step")
public class Step {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String command;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StepStatus status;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    // Written only by the worker; exposed through the logs endpoint, not the run response.
    @Column(columnDefinition = "TEXT", insertable = false, updatable = false)
    private String log;

    protected Step() {
    }

    public Step(int position, String name, String command) {
        this.position = position;
        this.name = name;
        this.command = command;
        this.status = StepStatus.PENDING;
    }

    void attachTo(Job job) {
        this.job = job;
    }

    public Long getId() { return id; }
    public Job getJob() { return job; }
    public int getPosition() { return position; }
    public String getName() { return name; }
    public String getCommand() { return command; }
    public StepStatus getStatus() { return status; }
    public Integer getExitCode() { return exitCode; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getLog() { return log; }
}
