package com.conveyorci.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A source repository that pipelines run for (e.g. owner "vennela", name "conveyor-ci"). */
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Project() {
    }

    public Project(String owner, String name, String defaultBranch) {
        this.owner = owner;
        this.name = name;
        this.defaultBranch = defaultBranch;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getDefaultBranch() { return defaultBranch; }
    public Instant getCreatedAt() { return createdAt; }
}
