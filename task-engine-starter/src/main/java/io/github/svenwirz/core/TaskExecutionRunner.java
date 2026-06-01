package io.github.svenwirz.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.svenwirz.api.TaskProcessor;
import io.github.svenwirz.config.TaskEngineProperties;
import io.github.svenwirz.model.Task;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Führt eine einzelne, bereits geclaimte Task aus.
 *
 * <ul>
 *   <li><b>R10:</b> Processor-Aufruf und finaler Status-Update laufen in <i>einer</i>
 *       {@link TransactionTemplate}-Transaktion.</li>
 *   <li><b>R3:</b> Beim Enqueue gestempelte Trace-IDs werden als Scope + MDC wieder geöffnet.</li>
 *   <li><b>R22:</b> Ein Watchdog interruptet den Worker-Thread bei Timeout-Überschreitung.</li>
 *   <li><b>R6/R21:</b> Fehlschläge werden gemäß Retry-Policy neu eingeplant oder auf DEAD gesetzt.</li>
 *   <li><b>R8:</b> Unbekannte Typen werden als Failure behandelt.</li>
 * </ul>
 */
public class TaskExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionRunner.class);

    private final TaskRepository repo;
    private final ProcessorRegistry registry;
    private final RetryPolicyResolver retryPolicyResolver;
    private final ObjectMapper objectMapper;
    private final TraceContextProvider traceContext;
    private final EngineMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final TaskEngineProperties properties;
    private final ScheduledExecutorService watchdog;

    public TaskExecutionRunner(TaskRepository repo,
                               ProcessorRegistry registry,
                               RetryPolicyResolver retryPolicyResolver,
                               ObjectMapper objectMapper,
                               TraceContextProvider traceContext,
                               EngineMetrics metrics,
                               TransactionTemplate transactionTemplate,
                               TaskEngineProperties properties,
                               ScheduledExecutorService watchdog) {
        this.repo = repo;
        this.registry = registry;
        this.retryPolicyResolver = retryPolicyResolver;
        this.objectMapper = objectMapper;
        this.traceContext = traceContext;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.watchdog = watchdog;
    }

    public void run(Task task) {
        MDC.put("taskId", String.valueOf(task.getId()));
        MDC.put("taskType", task.getType());
        if (task.getTraceId() != null) {
            MDC.put("traceId", task.getTraceId());
        }
        AtomicBoolean timedOut = new AtomicBoolean(false);
        long start = System.nanoTime();
        try (TraceContextProvider.Scope ignored =
                     traceContext.openScope(task.getTraceId(), task.getSpanId())) {
            metrics.started(task.getType());
            executeInTransaction(task, timedOut);
            metrics.succeeded(task.getType(), System.nanoTime() - start);
        } catch (Throwable ex) {
            handleFailure(task, ex, timedOut.get());
        } finally {
            // Etwaiges Interrupt-Flag des Watchdogs zurücksetzen, bevor der
            // Pool-Thread die nächste Task übernimmt.
            Thread.interrupted();
            MDC.clear();
        }
    }

    private void executeInTransaction(Task task, AtomicBoolean timedOut) {
        transactionTemplate.executeWithoutResult(status -> {
            TaskProcessor<?> processor = registry.find(task.getType())
                    .orElseThrow(() -> new io.github.svenwirz.api.UnknownTaskTypeException(task.getType()));
            Object payload = deserialize(task, processor);

            Duration timeout = resolveTimeout(processor);
            Thread worker = Thread.currentThread();
            ScheduledFuture<?> watch = null;
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                watch = watchdog.schedule(() -> {
                    timedOut.set(true);
                    worker.interrupt();
                }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            try {
                invoke(processor, payload);
            } catch (Exception e) {
                throw new ProcessingFailure(e);
            } finally {
                if (watch != null) {
                    watch.cancel(false);
                }
            }
            repo.markSucceeded(task.getId(), Instant.now());
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invoke(TaskProcessor<?> processor, Object payload) throws Exception {
        ((TaskProcessor) processor).process(payload);
    }

    private Object deserialize(Task task, TaskProcessor<?> processor) {
        Class<?> type = processor.payloadType();
        if (type == String.class || task.getPayload() == null) {
            return task.getPayload();
        }
        try {
            return objectMapper.readValue(task.getPayload(), type);
        } catch (Exception e) {
            throw new ProcessingFailure(
                    new IllegalArgumentException("Payload-Deserialisierung fehlgeschlagen: " + e.getMessage(), e));
        }
    }

    private Duration resolveTimeout(TaskProcessor<?> processor) {
        Duration fromProcessor = processor.timeout();
        return fromProcessor != null ? fromProcessor : properties.getTimeout();
    }

    private void handleFailure(Task task, Throwable ex, boolean timedOut) {
        Throwable cause = ex instanceof ProcessingFailure && ex.getCause() != null ? ex.getCause() : ex;
        int attempts = task.getAttempts() + 1;
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        Instant now = Instant.now();

        if (timedOut) {
            metrics.timedOut(task.getType());
            message = "Timeout: " + message;
        }

        if (attempts >= task.getMaxAttempts()) {
            log.warn("Task {} ({}) erschöpft nach {} Versuchen → DEAD: {}",
                    task.getId(), task.getType(), attempts, message);
            repo.markDead(task.getId(), attempts, message, now);
            metrics.dead(task.getType());
        } else {
            Duration backoff = retryPolicyResolver.backoffFor(task.getType(), attempts);
            Instant next = now.plus(backoff);
            log.info("Task {} ({}) fehlgeschlagen (Versuch {}/{}), Retry in {} ms: {}",
                    task.getId(), task.getType(), attempts, task.getMaxAttempts(), backoff.toMillis(), message);
            repo.markForRetry(task.getId(), attempts, next, message, now);
            metrics.failedWillRetry(task.getType());
        }
    }

    /** Markiert eine vom Processor geworfene Exception für die Retry-Behandlung. */
    private static final class ProcessingFailure extends RuntimeException {
        ProcessingFailure(Throwable cause) {
            super(cause);
        }
    }
}
