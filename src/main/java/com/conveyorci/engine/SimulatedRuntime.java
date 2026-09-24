package com.conveyorci.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A {@link ContainerRuntime} that starts no containers. It exists for load tests: it isolates the
 * engine (scheduling, dispatch, claims, leases, heartbeats, log streaming) from Docker's own
 * per-container start-up cost, which would otherwise dominate every number.
 *
 * <p>Commands are interpreted, not run. Parts separated by {@code &&} or {@code ;} run in order:
 * <ul>
 *   <li>{@code sleep S}: waits S seconds (fractions allowed); ends early if the job is killed</li>
 *   <li>{@code exit N}: stops with exit code N</li>
 *   <li>anything else: prints one line and succeeds</li>
 * </ul>
 * Enabled with {@code conveyor.runtime=simulated}. Never use it for real builds.
 */
@Component
@ConditionalOnProperty(name = "conveyor.runtime", havingValue = "simulated")
public class SimulatedRuntime implements ContainerRuntime {

    private static final Logger log = LoggerFactory.getLogger(SimulatedRuntime.class);
    private static final Pattern SLEEP = Pattern.compile("sleep\\s+(\\d*\\.?\\d+)");
    private static final Pattern EXIT = Pattern.compile("exit\\s+(\\d+)");
    private static final Pattern SEPARATOR = Pattern.compile("&&|;");
    static final int KILLED = 137; // what Docker reports for a container killed mid-command

    /** Counted down when a container is removed, which interrupts any sleep inside it. */
    private final Map<String, CountDownLatch> containers = new ConcurrentHashMap<>();

    public SimulatedRuntime() {
        log.warn("conveyor.runtime=simulated: jobs are NOT executed; this mode is for load testing only");
    }

    @Override
    public void start(String image, String containerName) {
        containers.put(containerName, new CountDownLatch(1));
    }

    @Override
    public void copyInto(String containerName, Path directory) {
        // nothing to copy into
    }

    @Override
    public ExecResult exec(String containerName, String command, Duration timeout, Consumer<String> onLine)
            throws InterruptedException {
        CountDownLatch removed = containers.get(containerName);
        if (removed == null || removed.getCount() == 0) {
            return new ExecResult(KILLED, false);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (String part : SEPARATOR.split(command)) {
            String cmd = part.strip();
            Matcher sleep = SLEEP.matcher(cmd);
            Matcher exit = EXIT.matcher(cmd);
            if (sleep.matches()) {
                long wantNanos = (long) (Double.parseDouble(sleep.group(1)) * 1_000_000_000L);
                long leftNanos = deadline - System.nanoTime();
                if (removed.await(Math.min(wantNanos, Math.max(0, leftNanos)), TimeUnit.NANOSECONDS)) {
                    return new ExecResult(KILLED, false);
                }
                if (wantNanos > leftNanos) {
                    return new ExecResult(-1, true);
                }
            } else if (exit.matches()) {
                return new ExecResult(Integer.parseInt(exit.group(1)), false);
            } else if (!cmd.isEmpty()) {
                onLine.accept("simulated: " + cmd);
            }
        }
        return new ExecResult(0, false);
    }

    @Override
    public void remove(String containerName) {
        CountDownLatch removed = containers.remove(containerName);
        if (removed != null) {
            removed.countDown();
        }
    }
}
