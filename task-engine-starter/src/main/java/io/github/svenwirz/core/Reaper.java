package io.github.svenwirz.core;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.svenwirz.config.TaskEngineProperties;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Crash-Recovery (R12). Setzt RUNNING-Tasks, deren Knoten vermutlich abgestürzt ist
 * (claimed_at älter als {@code stuck-after}), zurück auf PENDING, damit ein anderer
 * Knoten sie erneut claimen kann. Begründet die at-least-once-Semantik (R17).
 */
public class Reaper {

    private static final Logger log = LoggerFactory.getLogger(Reaper.class);

    private final TaskRepository repository;
    private final TaskEngineProperties properties;
    private final Wakeup wakeup;

    public Reaper(TaskRepository repository, TaskEngineProperties properties, Wakeup wakeup) {
        this.repository = repository;
        this.properties = properties;
        this.wakeup = wakeup;
    }

    public void runOnce() {
        Instant now = Instant.now();
        Instant stuckBefore = now.minus(properties.getStuckAfter());
        try {
            int requeued = repository.requeueStuck(stuckBefore, now);
            if (requeued > 0) {
                log.info("Reaper hat {} verwaiste RUNNING-Task(s) requeued", requeued);
                wakeup.signal();
            }
        } catch (RuntimeException e) {
            log.warn("Reaper-Lauf fehlgeschlagen: {}", e.getMessage());
        }
    }
}
