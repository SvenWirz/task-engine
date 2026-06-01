package io.github.svenwirz.core;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.svenwirz.config.TaskEngineProperties;

/**
 * Treibender Loop der Engine. Ein dedizierter Thread wartet auf ein Wakeup-Signal
 * (LISTEN/NOTIFY, freigewordene Kapazität) oder läuft nach Ablauf des Fallback-Poll-
 * Intervalls (R11) los und stößt eine Claim-Runde im {@link WorkerManager} an.
 *
 * <p>Mehrere Signale zwischen zwei Runden werden zu einem zusammengefasst (coalescing),
 * sodass NOTIFY-Stürme nicht in viele leere Claim-Runden münden.
 */
public class TaskDispatcher implements Wakeup {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final WorkerManager workerManager;
    private final TaskEngineProperties properties;
    private final Semaphore signal = new Semaphore(0);

    private volatile boolean running = false;
    private Thread thread;

    public TaskDispatcher(WorkerManager workerManager, TaskEngineProperties properties) {
        this.workerManager = workerManager;
        this.properties = properties;
    }

    @Override
    public void signal() {
        // Coalescing: höchstens ein ausstehendes Signal.
        if (signal.availablePermits() == 0) {
            signal.release();
        }
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "task-engine-dispatcher");
        thread.setDaemon(true);
        thread.start();
        // Beim Start einmal pumpen, um Liegengebliebenes aufzunehmen.
        signal();
    }

    public void stop() {
        running = false;
        signal.release();
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        long pollMs = Math.max(100, properties.getPollInterval().toMillis());
        while (running) {
            try {
                signal.tryAcquire(pollMs, TimeUnit.MILLISECONDS);
                signal.drainPermits();
                if (!running) {
                    return;
                }
                workerManager.pump();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Dispatcher-Runde fehlgeschlagen: {}", e.getMessage(), e);
            }
        }
    }
}
