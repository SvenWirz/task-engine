package io.github.svenwirz.metrics;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import io.github.svenwirz.core.EngineMetrics;
import io.github.svenwirz.model.TaskStatus;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Micrometer-Implementierung der {@link EngineMetrics} (R5). Counter pro Ergebnis,
 * Perzentil-Timer für die Verarbeitungsdauer und Queue-Tiefe-Gauges. Wird nur bei
 * vorhandenem Micrometer-Classpath aktiviert (R7).
 */
public class MicrometerEngineMetrics implements EngineMetrics {

    private final MeterRegistry registry;
    private final Counter claimedCounter;

    public MicrometerEngineMetrics(MeterRegistry registry, TaskRepository repository) {
        this.registry = registry;
        this.claimedCounter = registry.counter("taskengine.claimed");

        // Queue-Tiefe je relevantem Status als Gauge.
        registry.gauge("taskengine.queue.depth", Tags.of("status", "PENDING"), repository,
                r -> r.countByStatus(TaskStatus.PENDING));
        registry.gauge("taskengine.queue.depth", Tags.of("status", "RUNNING"), repository,
                r -> r.countByStatus(TaskStatus.RUNNING));
        registry.gauge("taskengine.queue.depth", Tags.of("status", "DEAD"), repository,
                r -> r.countByStatus(TaskStatus.DEAD));
    }

    @Override
    public void claimed(int count) {
        claimedCounter.increment(count);
    }

    @Override
    public void started(String type) {
        registry.counter("taskengine.started", "type", type).increment();
    }

    @Override
    public void succeeded(String type, long durationNanos) {
        Timer.builder("taskengine.processing")
                .tag("type", type)
                .tag("result", "succeeded")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void failedWillRetry(String type) {
        registry.counter("taskengine.failed", "type", type, "outcome", "retry").increment();
    }

    @Override
    public void dead(String type) {
        registry.counter("taskengine.failed", "type", type, "outcome", "dead").increment();
    }

    @Override
    public void timedOut(String type) {
        registry.counter("taskengine.timeout", "type", type).increment();
    }
}
