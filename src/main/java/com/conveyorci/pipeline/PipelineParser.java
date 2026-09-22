package com.conveyorci.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Parses pipeline YAML safely: {@link SafeConstructor} blocks arbitrary object instantiation,
 * and size/alias limits block "billion laughs" style payloads. Duplicate keys are rejected
 * because silently keeping the last one hides mistakes.
 */
@Component
public class PipelineParser {

    public static final int MAX_YAML_BYTES = 64 * 1024;

    private final PipelineDefinitionReader reader = new PipelineDefinitionReader();

    public PipelineDefinition parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new PipelineValidationException(List.of("pipeline YAML is empty"));
        }
        if (yaml.getBytes(StandardCharsets.UTF_8).length > MAX_YAML_BYTES) {
            throw new PipelineValidationException(
                    List.of("pipeline YAML exceeds the " + (MAX_YAML_BYTES / 1024) + " KB limit"));
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(MAX_YAML_BYTES);
        options.setNestingDepthLimit(20);

        // Yaml instances are not thread-safe, so create one per parse.
        Yaml yamlParser = new Yaml(new SafeConstructor(options));
        Object root;
        try {
            root = yamlParser.load(yaml);
        } catch (YAMLException e) {
            throw new PipelineValidationException(List.of("invalid YAML: " + summarize(e.getMessage())));
        }
        return reader.read(root);
    }

    private static String summarize(String message) {
        if (message == null) {
            return "could not parse document";
        }
        return message.strip().replaceAll("\\s+", " ");
    }
}
