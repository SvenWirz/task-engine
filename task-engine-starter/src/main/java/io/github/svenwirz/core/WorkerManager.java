package io.github.svenwirz.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.svenwirz.config.ProcessorLimit;
import io.github.svenwirz.config.TaskEngineProperties;
import io.github.svenwirz.model.Task;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Claimt Tasks und übergibt sie an den Worker-Pool, unter Beachtung der
 * Parallelitäts-Grenzen (R15) und globaler Backpressure.
 *
 * <ul>
 *   <li>Globales In-Flight-Semaphore = {@code concurrency} (Backpressure gegen den Pool).</li>
 *   <li>Pro-Typ-Semaphore = {@code per-node}-Limit (lokale Begrenzung).</li>
 *   <li>Cluster-weites Limit per DB-Zählung; striktere Grenze gewinnt.</li>
 * </ul>
 *
 * <p>{@link #pump()} ist {@code synchronized} — es läuft immer nur eine Claim-Runde
 * gleichzeitig (getrieben vom Dispatcher und von freiwerdender Kapazität).
 */
public class WorkerManager {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);

    private final TaskRepository repository;
    private final TaskExecutionRunner runner;
    private final Executor executor;
    private final TaskEngineProperties properties;
    private final EngineMetrics metrics;
    private final String nodeId;

    private final Semaphore inFlight;
    private final Map<String, Semaphore> perTypeSemaphores = new ConcurrentHashMap<>();

    private volatile Wakeup wakeup = () -> { };
    private volatile boolean running = true;

    public WorkerManager(TaskRepository repository,
                         TaskExecutionRunner runner,
                         Executor executor,
                         TaskEngineProperties properties,
                         EngineMetrics metrics,
                         String nodeId) {
        this.repository = repository;
        this.runner = runner;
        this.executor = executor;
        this.properties = properties;
        this.metrics = metrics;
        this.nodeId = nodeId;
        this.inFlight = new Semaphore(Math.max(1, properties.getConcurrency()));
    }

    public void setWakeup(Wakeup wakeup) {
        this.wakeup = wakeup;
    }

    public void stop() {
        this.running = false;
    }

    /**
     * Eine Claim-Runde: holt verfügbare Tasks, prüft Limits, übergibt an den Pool.
     * Läuft, bis keine Kapazität oder keine Tasks mehr vorhanden sind.
     */
    public synchronized void pump() {
        if (!running) {
            return;
        }
        while (running) {
            int free = inFlight.availablePermits();
            if (free <= 0) {
                return;
            }
            int toClaim = Math.min(properties.getBatchSize(), free);
            List<Task> claimed;
            try {
                claimed = repository.claimBatch(nodeId, toClaim, Instant.now());
            } catch (RuntimeException e) {
                log.warn("Claim-Runde fehlgeschlagen: {}", e.getMessage());
                return;
            }
            if (claimed.isEmpty()) {
                return;
            }
            metrics.claimed(claimed.size());
            for (Task task : claimed) {
                if (!admit(task)) {
                    repository.requeueClaimed(task.getId(), Instant.now());
                    continue;
                }
                submit(task);
            }
            // Weniger geholt als angefragt → Queue (vorerst) leer.
            if (claimed.size() < toClaim) {
                return;
            }
        }
    }

    /** Prüft und reserviert die nötigen Permits; gibt bei Ablehnung sauber zurück. */
    private boolean admit(Task task) {
        if (!inFlight.tryAcquire()) {
            return false;
        }
        Semaphore typeSem = perNodeSemaphore(task.getType());
        if (typeSem != null && !typeSem.tryAcquire()) {
            inFlight.release();
            return false;
        }
        Integer clusterLimit = clusterWideLimit(task.getType());
        if (clusterLimit != null) {
            // Die Task ist bereits als RUNNING gezählt; Limit gilt inkl. ihrer selbst.
            int runningNow = repository.countRunningByType(task.getType());
            if (runningNow > clusterLimit) {
                if (typeSem != null) {
                    typeSem.release();
                }
                inFlight.release();
                return false;
            }
        }
        return true;
    }

    private void submit(Task task) {
        Semaphore typeSem = perNodeSemaphore(task.getType());
        executor.execute(() -> {
            try {
                runner.run(task);
            } finally {
                if (typeSem != null) {
                    typeSem.release();
                }
                inFlight.release();
                // Kapazität frei → erneut versuchen zu claimen.
                wakeup.signal();
            }
        });
    }

    private Semaphore perNodeSemaphore(String type) {
        Integer limit = perNodeLimit(type);
        if (limit == null) {
            return null;
        }
        return perTypeSemaphores.computeIfAbsent(type, t -> new Semaphore(limit));
    }

    private Integer perNodeLimit(String type) {
        ProcessorLimit limit = properties.getProcessorLimits().get(type);
        if (limit != null && limit.getPerNode() != null) {
            return limit.getPerNode();
        }
        return null;
    }

    private Integer clusterWideLimit(String type) {
        ProcessorLimit limit = properties.getProcessorLimits().get(type);
        if (limit != null && limit.getClusterWide() != null) {
            return limit.getClusterWide();
        }
        return null;
    }
}
