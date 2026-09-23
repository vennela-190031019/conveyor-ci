package com.conveyorci.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.conveyorci.engine.ContainerRuntime.ExecResult;

/** Runs an external command with a timeout, streaming combined stdout/stderr line by line. */
public final class Processes {

    private Processes() {
    }

    public static ExecResult run(List<String> command, Duration timeout, Consumer<String> onLine)
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
