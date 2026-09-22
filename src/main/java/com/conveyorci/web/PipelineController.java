package com.conveyorci.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.conveyorci.pipeline.PipelineParser;
import com.conveyorci.pipeline.PipelineValidationException;
import com.conveyorci.web.ApiModels.ValidationResponse;

/** Dry-run validation: lets users (and a future editor UI) check a pipeline before pushing it. */
@RestController
public class PipelineController {

    private final PipelineParser parser;

    public PipelineController(PipelineParser parser) {
        this.parser = parser;
    }

    @PostMapping(value = "/api/pipelines/validate",
            consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml"})
    public ValidationResponse validate(@RequestBody String yaml) {
        try {
            return ValidationResponse.ok(parser.parse(yaml));
        } catch (PipelineValidationException e) {
            return ValidationResponse.invalid(e.getErrors());
        }
    }
}
