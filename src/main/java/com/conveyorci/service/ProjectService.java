package com.conveyorci.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conveyorci.domain.Project;
import com.conveyorci.domain.ProjectRepository;
import com.conveyorci.web.ApiModels.CreateProjectRequest;
import com.conveyorci.web.ApiModels.ProjectResponse;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        String branch = request.defaultBranch() == null || request.defaultBranch().isBlank()
                ? "main" : request.defaultBranch().strip();
        if (projects.existsByOwnerAndName(request.owner(), request.name())) {
            throw duplicate(request);
        }
        try {
            // saveAndFlush so a concurrent duplicate hits the unique constraint here, not at commit.
            return ProjectResponse.from(projects.saveAndFlush(new Project(request.owner(), request.name(), branch)));
        } catch (DataIntegrityViolationException e) {
            throw duplicate(request);
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projects.findAll(Sort.by("id")).stream().map(ProjectResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long id) {
        return projects.findById(id).map(ProjectResponse::from)
                .orElseThrow(() -> new NotFoundException("project " + id + " not found"));
    }

    private static ConflictException duplicate(CreateProjectRequest request) {
        return new ConflictException("project " + request.owner() + "/" + request.name() + " already exists");
    }
}
