package com.conveyorci.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {

    @Query("select coalesce(max(r.runNumber), 0) from PipelineRun r where r.project.id = :projectId")
    int findMaxRunNumber(@Param("projectId") Long projectId);

    List<PipelineRun> findTop50ByProject_IdOrderByRunNumberDesc(Long projectId);
}
