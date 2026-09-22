package com.conveyorci.pipeline;

/** A single shell command executed inside a job's container. */
public record StepDefinition(String name, String run) {
}
