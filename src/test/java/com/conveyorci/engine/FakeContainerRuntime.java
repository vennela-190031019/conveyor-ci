package com.conveyorci.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory stand-in for Docker so engine tests are fast and deterministic.
 * Commands it understands:
 * <ul>
 *   <li>{@code exit N}: fails with exit code N</li>
 *   <li>{@code flaky-<id>}: fails the first time it runs, succeeds after that</li>
 *   <li>{@code hang}: blocks until its container is removed (used to test cancellation)</li>
 *   <li>anything else: prints the command and succeeds</li>
 * </ul>
 */
public class FakeContainerRuntime implements ContainerRuntime {

    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> flakyRuns = new ConcurrentHashMap<>();
    private final List<String> executed = new CopyOnWriteArrayList<>();

    @Override
    public void start(String image, String containerName) {
        removed.remove(containerName);
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

    public List<String> executedCommands() {
        return List.copyOf(executed);
    }
}
