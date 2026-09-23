package com.conveyorci.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Runs a job's steps inside an isolated container. One container per job attempt; each
 * step is executed in it, so files written by one step are visible to the next.
 */
public interface ContainerRuntime {

    /** Starts a long-lived container for the job. Pulls the image if needed. */
    void start(String image, String containerName) throws IOException, InterruptedException;

    /** Runs a shell command in the container, streaming each output line to {@code onLine}. */
    ExecResult exec(String containerName, String command, Duration timeout, Consumer<String> onLine)
            throws IOException, InterruptedException;

    /** Force-removes the container, killing anything still running in it. Idempotent. */
    void remove(String containerName);

    record ExecResult(int exitCode, boolean timedOut) {
    }
}
