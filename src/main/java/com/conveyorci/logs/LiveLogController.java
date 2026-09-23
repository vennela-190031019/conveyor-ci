package com.conveyorci.logs;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.conveyorci.logs.LiveLogService.Subscription;

/**
 * Streams a job's output as Server-Sent Events: {@code lines}, {@code reset} (a retry started) and
 * {@code end}. SSE rather than WebSockets because the flow is one-way; it's plain HTTP (works
 * through proxies like ngrok), and browsers reconnect automatically.
 */
@RestController
public class LiveLogController {

    private static final Duration MAX_STREAM = Duration.ofHours(1);

    private final LiveLogService service;

    public LiveLogController(LiveLogService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/jobs/{jobId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long jobId) {
        SseEmitter emitter = new SseEmitter(MAX_STREAM.toMillis());
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        Runnable detach = () -> {
            Subscription s = subscription.get();
            if (s != null) {
                s.cancel();
            }
        };
        emitter.onCompletion(detach);
        emitter.onTimeout(detach);
        emitter.onError(e -> detach.run());

        subscription.set(service.subscribe(jobId, new LiveLogService.Sink() {
            @Override
            public void send(String event, Object data) throws IOException {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            }

            @Override
            public void keepAlive() throws IOException {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            }

            @Override
            public void complete() {
                emitter.complete();
            }
        }));
        return emitter;
    }
}
