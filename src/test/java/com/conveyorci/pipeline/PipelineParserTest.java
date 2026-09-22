package com.conveyorci.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PipelineParserTest {

    private final PipelineParser parser = new PipelineParser();

    private List<String> errorsFor(String yaml) {
        try {
            parser.parse(yaml);
        } catch (PipelineValidationException e) {
            return e.getErrors();
        }
        throw new AssertionError("expected pipeline to be invalid");
    }

    @Test
    void parsesDiamondIntoParallelStages() {
        PipelineDefinition def = parser.parse("""
                name: ci
                jobs:
                  build:
                    image: maven:3.9-eclipse-temurin-21
                    steps:
                      - name: Compile
                        run: mvn -B compile
                  unit-tests:
                    image: maven:3.9-eclipse-temurin-21
                    needs: build
                    retries: 2
                    timeout_minutes: 15
                    steps:
                      - run: mvn -B test
                  lint:
                    image: node:20
                    needs: [build]
                    steps:
                      - run: npm run lint
                  deploy:
                    image: alpine:3.20
                    needs: [unit-tests, lint]
                    steps:
                      - run: ./deploy.sh
                """);

        assertThat(def.name()).isEqualTo("ci");
        assertThat(def.stages()).containsExactly(
                List.of("build"), List.of("unit-tests", "lint"), List.of("deploy"));

        JobDefinition unit = def.jobs().get(1);
        assertThat(unit.needs()).containsExactly("build");
        assertThat(unit.retries()).isEqualTo(2);
        assertThat(unit.timeoutMinutes()).isEqualTo(15);
        assertThat(unit.steps()).containsExactly(new StepDefinition("Step 1", "mvn -B test"));
    }

    @Test
    void stageIsLongestDependencyChain() {
        PipelineDefinition def = parser.parse("""
                jobs:
                  c: { image: x, needs: [a, b], steps: [ { run: c } ] }
                  a: { image: x, steps: [ { run: a } ] }
                  b: { image: x, needs: a, steps: [ { run: b } ] }
                """);
        assertThat(def.name()).isEqualTo("pipeline");
        assertThat(def.stages()).containsExactly(List.of("a"), List.of("b"), List.of("c"));
    }

    @Test
    void reportsCycleWithPath() {
        assertThat(errorsFor("""
                jobs:
                  a: { image: x, needs: b, steps: [ { run: a } ] }
                  b: { image: x, needs: c, steps: [ { run: b } ] }
                  c: { image: x, needs: a, steps: [ { run: c } ] }
                """)).containsExactly("dependency cycle detected: a -> b -> c -> a");
    }

    @Test
    void rejectsSelfAndUnknownDependencies() {
        assertThat(errorsFor("""
                jobs:
                  a: { image: x, needs: [a, ghost], steps: [ { run: a } ] }
                """)).containsExactly(
                "job 'a' cannot depend on itself",
                "job 'a' needs unknown job 'ghost'");
    }

    @Test
    void collectsAllStructuralErrorsInOnePass() {
        List<String> errors = errorsFor("""
                jobs:
                  a:
                    steps: [ { run: echo } ]
                  b:
                    image: x
                    step: [ { run: echo } ]
                  c:
                    image: x
                    retries: 9
                    steps: [ "echo not-a-map" ]
                """);
        assertThat(errors).hasSize(5);
        assertThat(errors).anyMatch(e -> e.contains("job 'a': 'image' is required"));
        assertThat(errors).anyMatch(e -> e.contains("job 'b': unknown key(s) [step]"));
        assertThat(errors).anyMatch(e -> e.contains("job 'b': 'steps' is required"));
        assertThat(errors).anyMatch(e -> e.contains("job 'c': 'retries' must be a whole number between 0 and 5"));
        assertThat(errors).anyMatch(e -> e.contains("job 'c' step 1: must be a mapping"));
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThat(errorsFor("""
                jobs:
                  a: { image: x, steps: [ { run: a } ] }
                  a: { image: y, steps: [ { run: b } ] }
                """)).singleElement().asString().startsWith("invalid YAML:");
    }

    @Test
    void rejectsMalformedAndEmptyInput() {
        assertThat(errorsFor("jobs: [unclosed")).singleElement().asString().startsWith("invalid YAML:");
        assertThat(errorsFor("   ")).containsExactly("pipeline YAML is empty");
        assertThat(errorsFor("- just\n- a list")).containsExactly("pipeline must be a YAML mapping with a 'jobs' key");
        assertThat(errorsFor("jobs: {}")).singleElement().asString().contains("non-empty mapping");
    }

    @Test
    void refusesToInstantiateArbitraryTypes() {
        // SafeConstructor must reject global tags that would construct Java objects.
        assertThatThrownBy(() -> parser.parse("jobs: !!javax.script.ScriptEngineManager [x]"))
                .isInstanceOf(PipelineValidationException.class);
    }

    @Test
    void rejectsOversizedDocuments() {
        String huge = "name: x\n# " + "a".repeat(PipelineParser.MAX_YAML_BYTES) + "\n";
        assertThat(errorsFor(huge)).singleElement().asString().contains("KB limit");
    }
}
