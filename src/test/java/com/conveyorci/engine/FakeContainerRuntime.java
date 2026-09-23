package com.conveyorci.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * In-memory stand-in for Docker so engine tests are fast and deterministic.
 * Commands it understands:
 * <ul>
 *   <li>{@code exit N}: fails with exit code N</li>
 *   <li>{@code flaky-<id>}: fails the first time it runs, succeeds after that</li>
 *   <li>{@code hang}: blocks until its container is removed (used to test cancellation)</li>
 *   <li>{@code lines N}: prints N lines at once; {@code tick N}: prints N lines 40 ms apart</li>
 *   <li>anything else: prints the command and succeeds</li>
 * </ul>
 */
public class FakeContainerRuntime implements ContainerRuntime {

    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> flakyRuns = new ConcurrentHashMap<>();
    private final List<String> executed = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> copiedFiles = new ConcurrentHashMap<>();

    @Override
    public void start(String image, String containerName) {
        removed.remove(containerName);
    }

    /** Records the relative paths of the files that would be copied into /workspace. */
    @Override
    public void copyInto(String containerName, Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            copiedFiles.put(containerName, files.filter(Files::isRegularFile)
                    .map(f -> directory.relativize(f).toString().replace('\\', '/'))
                    .sorted().toList());
        }
    }

    @Override
    public ExecResult exec(String containerName, String command, Duration timeout, Consumer<String> onLine)
            throws InterruptedException {
        executed.add(command);
        if (command.startsWith("exit ")) {
            onLine.accept("failing on purpose");
            return new ExecResult(Integer.parseInt(command.substring(5).trim()), false);
        }
        if (command.startsWith("flaky-")) {
            int runs = flakyRuns.merge(command, 1, Integer::sum);
            onLine.accept("flaky run #" + runs);
            return new ExecResult(runs == 1 ? 1 : 0, false);
        }
        if (command.startsWith("lines ")) {
            int count = Integer.parseInt(command.substring(6).trim());
            for (int i = 1; i <= count; i++) {
                onLine.accept("line " + i);
            }
            return new ExecResult(0, false);
        }
        if (command.startsWith("tick ")) {
            int count = Integer.parseInt(command.substring(5).trim());
            for (int i = 1; i <= count; i++) {
                onLine.accept("tick " + i);
                Thread.sleep(40);
            }
            return new ExecResult(0, false);
        }
        if (command.equals("hang")) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!removed.contains(containerName)) {
                if (System.nanoTime() > deadline) {
                    return new ExecResult(-1, true);
                }
                Thread.sleep(20);
            }
            return new ExecResult(137, false); // killed
        }
        onLine.accept("ran: " + command);
        return new ExecResult(0, false);
    }

    @Override
    public void remove(String containerName) {
        removed.add(containerName);
    }

    public boolean wasRemoved(String containerName) {
        return removed.contains(containerName);
    }

    public List<String> copiedFiles(String containerName) {
        return copiedFiles.getOrDefault(containerName, List.of());
    }

    public List<String> executedCommands() {
        return List.copyOf(executed);
    }
}
