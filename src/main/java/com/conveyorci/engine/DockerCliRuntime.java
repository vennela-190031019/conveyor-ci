package com.conveyorci.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(5);

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
        runOrThrow(List.of(docker, "run", "-d",
                "--name", containerName,
                "--label", "conveyor.managed=true",
                "--memory", memoryLimit,
                "--cpus", cpuLimit,
                "-w", "/workspace",
                "--entrypoint", "tail",
                image, "-f", "/dev/null"), START_TIMEOUT, "could not start container from image '" + image + "'");
    }

    @Override
    public void copyInto(String containerName, Path directory) throws IOException, InterruptedException {
        // The trailing "/." copies the directory's contents rather than the directory itself.
        runOrThrow(List.of(docker, "cp", directory.toAbsolutePath() + "/.", containerName + ":/workspace"),
                COPY_TIMEOUT, "could not copy source code into the container");
    }

    @Override
    public ExecResult exec(String containerName, String command, Duration timeout, Consumer<String> onLine)
            throws IOException, InterruptedException {
        return Processes.run(List.of(docker, "exec", containerName, "sh", "-c", command), timeout, onLine);
    }

    @Override
    public void remove(String containerName) {
        try {
            Processes.run(List.of(docker, "rm", "-f", containerName), Duration.ofSeconds(30), line -> { });
        } catch (IOException e) {
            log.warn("failed to remove container {}: {}", containerName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runOrThrow(List<String> command, Duration timeout, String error)
            throws IOException, InterruptedException {
        List<String> output = new ArrayList<>();
        ExecResult result = Processes.run(command, timeout, output::add);
        if (result.timedOut() || result.exitCode() != 0) {
            String tail = String.join(" ", output.subList(Math.max(0, output.size() - 5), output.size()));
            throw new IOException(error + (result.timedOut() ? " (timed out)" : ": " + tail));
        }
    }
}
