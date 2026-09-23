package com.conveyorci.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    boolean existsByOwnerAndName(String owner, String name);

    /** GitHub owner and repository names are case-insensitive. */
    Optional<Project> findFirstByOwnerIgnoreCaseAndNameIgnoreCaseOrderByIdAsc(String owner, String name);

    /**
     * Row-locks the project (SELECT ... FOR UPDATE). Used when allocating the next run number
     * so two concurrent triggers for the same project can't both get #N.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") Long id);
}
