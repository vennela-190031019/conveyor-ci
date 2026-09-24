package com.conveyorci.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.conveyorci.domain.Statuses.StepStatus;
import com.conveyorci.engine.ContainerRuntime.ExecResult;
import com.conveyorci.engine.JobStore.ClaimedJob;
import com.conveyorci.engine.JobStore.StepSpec;
import com.conveyorci.logs.LiveLogPublisher;
import com.conveyorci.logs.LiveLogPublisher.JobLog;

/**
 * Pulls job ids from Redis, claims them in Postgres, and runs each job's steps in a container.
 *
 * <p>While a job runs, a heartbeat extends its lease every {@code leaseSeconds / 3}. If this
 * process dies, heartbeats stop, the lease expires, and the scheduler re-queues the job for
 * another worker. If a heartbeat reports the job is no longer ours (the run was cancelled, or
 * the lease was lost during a network partition), the container is killed immediately.
 *
 * <p>Runs in the API process by default. Start dedicated worker nodes with
 * {@code --conveyor.scheduler.enabled=false --spring.main.web-application-type=none}.
 */
@Component
@ConditionalOnProperty(name = "conveyor.worker.enabled", havingValue = "true", matchIfMissing = true)
public class Worker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_LOG_CHARS = 64 * 1024;
    private static final double RETRY_JITTER = 0.2;

    private final JobQueue queue;
    private final JobStore store;
    private final ContainerRuntime runtime;
    private final SourceFetcher sourceFetcher;
    private final LiveLogPublisher liveLogs;
    private final SchedulerWakeup wakeup;
    private final String workerId;
    private final String hostname;
    private final int concurrency;
    private final int leaseSeconds;
    private final long retryBaseMillis;
    private final long retryMaxMillis;

    private final AtomicInteger activeJobs = new AtomicInteger();
    private volatile boolean running;
    private ExecutorService loops;
    private ScheduledExecutorService timers;

    public Worker(JobQueue queue, JobStore store, ContainerRuntime runtime, SourceFetcher sourceFetcher,
                  LiveLogPublisher liveLogs, SchedulerWakeup wakeup,
                  @Value("${conveyor.worker.id:}") String configuredId,
                  @Value("${conveyor.worker.concurrency:2}") int concurrency,
                  @Value("${conveyor.worker.lease-seconds:30}") int leaseSeconds,
                  @Value("${conveyor.retry.base-delay-ms:2000}") long retryBaseMillis,
                  @Value("${conveyor.retry.max-delay-ms:60000}") long retryMaxMillis) {
        this.queue = queue;
        this.store = store;
        this.runtime = runtime;
        this.sourceFetcher = sourceFetcher;
        this.liveLogs = liveLogs;
        this.wakeup = wakeup;
        this.hostname = resolveHostname();
        this.workerId = configuredId == null || configuredId.isBlank()
                ? hostname + "-" + UUID.randomUUID().toString().substring(0, 8)
                : configuredId;
        this.concurrency = concurrency;
        this.leaseSeconds = leaseSeconds;
        this.retryBaseMillis = retryBaseMillis;
        this.retryMaxMillis = retryMaxMillis;
    }

    public String workerId() {
        return workerId;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        AtomicInteger threadNumber = new AtomicInteger();
        loops = Executors.newFixedThreadPool(concurrency,
                r -> new Thread(r, "worker-" + threadNumber.incrementAndGet()));
        timers = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "worker-heartbeat");
            t.setDaemon(true);
            return t;
        });
        timers.scheduleAtFixedRate(this::registerSelf, 0, 5, TimeUnit.SECONDS);
        for (int i = 0; i < concurrency; i++) {
            loops.submit(this::pollLoop);
        }
        log.info("worker {} started with {} slot(s), lease {}s", workerId, concurrency, leaseSeconds);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        // In-flight jobs are interrupted; their leases expire and the scheduler re-queues them.
        loops.shutdownNow();
        timers.shutdownNow();
        try {
            loops.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<Long> jobId = queue.pop(POLL_TIMEOUT);
                if (jobId.isEmpty()) {
                    continue;
                }
                Optional<ClaimedJob> claimed = store.claim(jobId.get(), workerId, leaseSeconds);
                if (claimed.isPresent()) {
                    activeJobs.incrementAndGet();
                    try {
                        execute(claimed.get());
                    } finally {
                        activeJobs.decrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                log.warn("worker loop error: {}", e.getMessage());
                sleepQuietly(1000);
            }
        }
    }

    void execute(ClaimedJob job) throws InterruptedException {
        String container = "conveyor-job-" + job.id() + "-" + job.attempt();
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        long heartbeatMillis = Math.max(200, leaseSeconds * 1000L / 3);

        ScheduledFuture<?> heartbeat = timers.scheduleAtFixedRate(() -> {
            try {
                if (!store.heartbeat(job.id(), workerId, job.attempt(), leaseSeconds)) {
                    if (ownershipLost.compareAndSet(false, true)) {
                        log.info("job {} is no longer owned by {} (cancelled or lease lost); stopping it",
                                job.id(), workerId);
                        runtime.remove(container);
                    }
                }
            } catch (RuntimeException e) {
                // Transient DB error: keep trying. If it persists, the lease simply expires.
                log.warn("heartbeat for job {} failed: {}", job.id(), e.getMessage());
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);

        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(job.timeoutMinutes());
        String failure = null;
        JobLog live = liveLogs.open(job.id(), job.attempt());
        String finalStatus = null;
        try {
            log.info("job {} attempt {}/{}: starting {} on image {}", job.id(), job.attempt(),
                    job.maxAttempts(), container, job.image());
            runtime.start(job.image(), container);

            // Checkout output is shown at the top of the first step's log.
            String checkoutLog = null;
            if (job.checkout() != null) {
                LogBuffer checkoutOutput = new LogBuffer(8 * 1024);
                try {
                    sourceFetcher.checkout(job.checkout(), runtime, container, line -> {
                        checkoutOutput.appendLine(line);
                        live.append(0, line);
                    });
                } catch (IOException | RuntimeException e) {
                    throw new IOException("checkout of " + job.checkout().owner() + "/" + job.checkout().repo()
                            + "@" + job.checkout().sha() + " failed: " + e.getMessage(), e);
                }
                checkoutLog = checkoutOutput.toString().stripTrailing();
            }

            for (StepSpec step : job.steps()) {
                Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
                if (remaining.isNegative() || remaining.isZero()) {
                    failure = "job exceeded its " + job.timeoutMinutes() + " minute timeout";
                    break;
                }
                if (!store.startStep(step.id(), job.id(), workerId, job.attempt())) {
                    ownershipLost.set(true);
                    break;
                }
                LogBuffer output = new LogBuffer(MAX_LOG_CHARS);
                if (checkoutLog != null) {
                    output.appendLine(checkoutLog);
                    checkoutLog = null;
                }
                output.appendLine("$ " + step.command());
                live.append(step.position(), "$ " + step.command());
                ExecResult result = runtime.exec(container, step.command(), remaining, line -> {
                    output.appendLine(line);
                    live.append(step.position(), line);
                });
                if (ownershipLost.get()) {
                    break;
                }

                boolean ok = !result.timedOut() && result.exitCode() == 0;
                store.finishStep(step.id(), job.id(), workerId, job.attempt(),
                        ok ? StepStatus.SUCCEEDED : StepStatus.FAILED,
                        result.timedOut() ? null : result.exitCode(), output.toString());
                if (result.timedOut()) {
                    failure = "step '" + step.name() + "' exceeded the job's " + job.timeoutMinutes()
                            + " minute timeout";
                    break;
                }
                if (!ok) {
                    failure = "step '" + step.name() + "' exited with code " + result.exitCode();
                    break;
                }
            }
        } catch (InterruptedException e) {
            // Shutting down: don't report anything. The lease expires and another worker retries.
            live.close(null);
            throw e;
        } catch (Exception e) {
            failure = "could not run job: " + e.getMessage();
        } finally {
            heartbeat.cancel(false);
            runtime.remove(container);
        }

        if (ownershipLost.get()) {
            live.close(null);
            return;
        }
        // Make every line visible before the job's status flips, so a viewer never sees
        // "finished" with output still missing.
        if (failure != null) {
            live.append(-1, "attempt " + job.attempt() + " failed: " + failure);
        }
        live.flushNow();
        if (failure == null) {
            if (store.completeSuccess(job.id(), workerId, job.attempt())) {
                finalStatus = "SUCCEEDED";
            }
            log.info("job {} succeeded on attempt {}", job.id(), job.attempt());
        } else {
            long backoff = Backoff.delayMillis(job.attempt(), retryBaseMillis, retryMaxMillis, RETRY_JITTER,
                    () -> ThreadLocalRandom.current().nextDouble());
            Optional<String> next = store.completeFailure(job.id(), workerId, job.attempt(), failure, backoff);
            if (next.filter("FAILED"::equals).isPresent()) {
                finalStatus = "FAILED";
            }
            log.info("job {} attempt {} failed ({}); job is now {}", job.id(), job.attempt(), failure,
                    next.orElse("owned by someone else"));
        }
        // Dependents can start (or be skipped) and the run may be finished: don't wait for the next pass.
        wakeup.wake();
        live.close(finalStatus);
    }

    private void registerSelf() {
        try {
            store.registerWorker(workerId, hostname, concurrency);
        } catch (RuntimeException e) {
            log.warn("worker registration heartbeat failed: {}", e.getMessage());
        }
    }

    private static String resolveHostname() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        return host == null || host.isBlank() ? "local" : host;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
