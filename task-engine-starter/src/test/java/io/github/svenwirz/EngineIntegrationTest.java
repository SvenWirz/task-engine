package io.github.svenwirz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.svenwirz.api.EnqueueRequest;
import io.github.svenwirz.api.TaskProcessor;
import io.github.svenwirz.api.TaskService;
import io.github.svenwirz.core.TraceContextProvider;
import io.github.svenwirz.model.TaskStatus;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * End-to-End-Integration der Worker-Seite gegen H2. Da das Repository einen portablen
 * Claim-Pfad und der Dispatcher einen Fallback-Poll besitzt, läuft die komplette
 * Verarbeitungsschleife ohne PostgreSQL.
 *
 * <p>Abgedeckt: Dispatch (R2), Retry &amp; DEAD (R6), unbekannte Typen (R8),
 * Timeout (R22), typisierte Payloads (R25) und Trace-Propagation (R3).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:te-engine;DB_CLOSE_DELAY=-1",
        "taskengine.enabled=true",
        "taskengine.poll-interval=120ms",
        "taskengine.concurrency=4",
        "taskengine.reaper-interval=1h",
        "taskengine.default-retry.base-backoff=20ms",
        "taskengine.default-retry.jitter=0"
})
class EngineIntegrationTest {

    @Autowired
    TaskService taskService;
    @Autowired
    TaskRepository repository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Recorder recorder;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task");
        recorder.reset();
    }

    private TaskStatus status(java.util.UUID id) {
        return repository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void processesTaskSuccessfully() { // R2
        var id = taskService.enqueue("ok", "{\"value\":42}");

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.SUCCEEDED));
        assertThat(recorder.okPayloads).contains("{\"value\":42}");
    }

    @Test
    void retriesAndEventuallyMarksDead() { // R6
        var id = taskService.enqueue(EnqueueRequest.of("fail", "{}").maxAttempts(3).build());

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.DEAD));

        var task = repository.findById(id).orElseThrow();
        assertThat(task.getAttempts()).isEqualTo(3);
        assertThat(task.getLastError()).contains("boom");
        // Drei reale Verarbeitungsversuche.
        assertThat(recorder.failAttempts.get()).isEqualTo(3);
    }

    @Test
    void unknownTypeFailsSafely() { // R8
        var id = taskService.enqueue(EnqueueRequest.of("no-such-processor", "{}").maxAttempts(1).build());

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.DEAD));
        assertThat(repository.findById(id).orElseThrow().getLastError())
                .containsIgnoringCase("kein taskprocessor");
    }

    @Test
    void timeoutAbortsAndCountsAsFailure() { // R22
        var id = taskService.enqueue(EnqueueRequest.of("slow", "{}").maxAttempts(1).build());

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.DEAD));
        assertThat(repository.findById(id).orElseThrow().getLastError())
                .containsIgnoringCase("timeout");
    }

    @Test
    void deserializesTypedPayload() { // R25
        var id = taskService.enqueue("typed", new Email("a@b.de", "Hallo"));

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.SUCCEEDED));
        assertThat(recorder.typedPayloads).hasSize(1);
        assertThat(recorder.typedPayloads.get(0)).isEqualTo(new Email("a@b.de", "Hallo"));
    }

    @Test
    void propagatesTraceContext() { // R3
        var id = taskService.enqueue("ok", "{\"trace\":true}");

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.SUCCEEDED));

        // Beim Enqueue gestempelt …
        assertThat(repository.findById(id).orElseThrow().getTraceId()).isEqualTo("trace-abc");
        // … und bei der Verarbeitung als Scope/MDC wieder geöffnet.
        assertThat(recorder.openedTraceIds).contains("trace-abc");
        assertThat(recorder.mdcTraceIdsDuringProcessing).contains("trace-abc");
    }

    // ------------------------------------------------------------------ Testbeans

    record Email(String to, String subject) {
    }

    /** Sammelt Beobachtungen aus den Processors für die Assertions. */
    static class Recorder {
        final List<String> okPayloads = new CopyOnWriteArrayList<>();
        final List<Email> typedPayloads = new CopyOnWriteArrayList<>();
        final AtomicInteger failAttempts = new AtomicInteger();
        final List<String> openedTraceIds = new CopyOnWriteArrayList<>();
        final List<String> mdcTraceIdsDuringProcessing = new CopyOnWriteArrayList<>();

        void reset() {
            okPayloads.clear();
            typedPayloads.clear();
            failAttempts.set(0);
            openedTraceIds.clear();
            mdcTraceIdsDuringProcessing.clear();
        }
    }

    @TestConfiguration
    static class Processors {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        TaskProcessor<String> okProcessor(Recorder recorder) {
            return new TaskProcessor<>() {
                @Override
                public String type() {
                    return "ok";
                }

                @Override
                public void process(String payload) {
                    recorder.okPayloads.add(payload);
                    String mdcTrace = MDC.get("traceId");
                    if (mdcTrace != null) {
                        recorder.mdcTraceIdsDuringProcessing.add(mdcTrace);
                    }
                }
            };
        }

        @Bean
        TaskProcessor<Email> typedProcessor(Recorder recorder) {
            return new TaskProcessor<Email>() {
                @Override
                public String type() {
                    return "typed";
                }

                @Override
                public void process(Email payload) {
                    recorder.typedPayloads.add(payload);
                }
            };
        }

        @Bean
        TaskProcessor<String> failingProcessor(Recorder recorder) {
            return new TaskProcessor<>() {
                @Override
                public String type() {
                    return "fail";
                }

                @Override
                public void process(String payload) {
                    recorder.failAttempts.incrementAndGet();
                    throw new RuntimeException("boom");
                }
            };
        }

        @Bean
        TaskProcessor<String> slowProcessor() {
            return new TaskProcessor<>() {
                @Override
                public String type() {
                    return "slow";
                }

                @Override
                public void process(String payload) throws InterruptedException {
                    // Respektiert Interrupt (R22): Watchdog interruptet nach timeout().
                    Thread.sleep(10_000);
                }

                @Override
                public Duration timeout() {
                    return Duration.ofMillis(200);
                }
            };
        }

        @Bean
        TraceContextProvider traceContextProvider(Recorder recorder) {
            return new TraceContextProvider() {
                @Override
                public String currentTraceId() {
                    return "trace-abc";
                }

                @Override
                public String currentSpanId() {
                    return "span-xyz";
                }

                @Override
                public Scope openScope(String traceId, String spanId) {
                    if (traceId != null) {
                        recorder.openedTraceIds.add(traceId);
                    }
                    return NOOP_SCOPE;
                }
            };
        }
    }
}
