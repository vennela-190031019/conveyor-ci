package com.conveyorci.pipeline;

import java.util.List;

/** Thrown when a pipeline definition is invalid. Carries every error found, not just the first. */
public class PipelineValidationException extends RuntimeException {

    private final List<String> errors;

    public PipelineValidationException(List<String> errors) {
        super("Invalid pipeline: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
