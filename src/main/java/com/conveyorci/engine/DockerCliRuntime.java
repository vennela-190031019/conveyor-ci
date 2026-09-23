package com.conveyorci.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link ContainerRuntime} backed by the Docker CLI. Using the CLI rather than a Docker SDK
 * keeps the worker dependency-free and works with any engine the CLI can reach
 * (Docker Desktop, Colima, remote DOCKER_HOST).
 */
@Component
public class DockerCliRuntime implements ContainerRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerCliRuntime.class);
    private static final Duration START_TIMEOUT = Duration.ofMinutes(10); // includes image pull

    private final String docker;
    private final String memoryLimit;
    private final String cpuLimit;

    public DockerCliRuntime(@Value("${conveyor.docker.binary:docker}") String docker,
                            @Value("${conveyor.docker.memory:1g}") String memoryLimit,
                            @Value("${conveyor.docker.cpus:1}") String cpuLimit) {
        this.docker = docker;
        this.memoryLimit = memoryLimit;
        this.cpuLimit = cpuLimit;
    }

    @Override
    public void start(String image, String containerName) throws IOException, InterruptedException {
        // `tail -f /dev/null` keeps the container alive between steps and exists in every base image.
        List<String> command = List.of(docker, "run", "-d",
                "--name", containerName,
                "--label", "conveyor.managed=true",
                "--memory", memoryLimit,
                "--cpus", cpuLimit,
                "-w", "/workspace",
                "--entrypoint", "tail",
                image, "-f", "/dev/null");
        List<String> output = new ArrayList<>();
        ExecResult result = run(command, START_TIMEOUT, output::add);
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IOException("could not start container from image '" + image + "': "
                    + String.join(" ", output.subList(Math.max(0, output.size() - 5), output.size())));
        }
    }

    @Override
    public ExecResult exec(String containerName, String command, Duration timeout, Consumer<String> onLine)
            throws IOException, InterruptedException {
        return run(List.of(docker, "exec", containerName, "sh", "-c", command), timeout, onLine);
    }

    @Override
    public void remove(String containerName) {
        try {
            run(List.of(docker, "rm", "-f", containerName), Duration.ofSeconds(30), line -> { });
        } catch (IOException e) {
            log.warn("failed to remove container {}: {}", containerName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ExecResult run(List<String> command, Duration timeout, Consumer<String> onLine)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException ignored) {
                // stream closes when the process is killed
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(1000);
                return new ExecResult(-1, true);
            }
            reader.join(5000);
            return new ExecResult(process.exitValue(), false);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
    }
}
