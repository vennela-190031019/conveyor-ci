package com.conveyorci.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.conveyorci.engine.ContainerRuntime.ExecResult;

class SimulatedRuntimeTest {

    private final SimulatedRuntime runtime = new SimulatedRuntime();
    private final List<String> lines = new ArrayList<>();

    private ExecResult exec(String command, Duration timeout) throws InterruptedException {
        return runtime.exec("c1", command, timeout, lines::add);
    }

    @Test
    void sleepsForTheRequestedTimeThenSucceeds() throws Exception {
        runtime.start("alpine", "c1");
        long start = System.nanoTime();
        ExecResult result = exec("echo hi && sleep 0.2", Duration.ofSeconds(5));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result).isEqualTo(new ExecResult(0, false));
        assertThat(tookMs).isBetween(180L, 2000L);
        assertThat(lines).containsExactly("simulated: echo hi");
    }

    @Test
    void exitStopsWithThatCode() throws Exception {
        runtime.start("alpine", "c1");
        assertThat(exec("echo a; exit 3; echo never", Duration.ofSeconds(5))).isEqualTo(new ExecResult(3, false));
        assertThat(lines).containsExactly("simulated: echo a");
    }

    @Test
    void sleepPastTheTimeoutTimesOut() throws Exception {
        runtime.start("alpine", "c1");
        assertThat(exec("sleep 10", Duration.ofMillis(100))).isEqualTo(new ExecResult(-1, true));
    }

    @Test
    void removingTheContainerKillsARunningSleep() throws Exception {
        runtime.start("alpine", "c1");
        CompletableFuture<ExecResult> running = CompletableFuture.supplyAsync(() -> {
            try {
                return exec("sleep 30", Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(100);
        runtime.remove("c1");

        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(new ExecResult(SimulatedRuntime.KILLED, false));
        assertThat(exec("echo after", Duration.ofSeconds(1)).exitCode()).isEqualTo(SimulatedRuntime.KILLED);
    }
}
