package io.github.svenwirz.actuator;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import io.github.svenwirz.model.TaskStatus;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Actuator-Health-Indikator {@code taskEngine} (R9). Meldet UP, solange die
 * task-Tabelle erreichbar ist, und exponiert Queue-Tiefen als Detail.
 */
public class TaskEngineHealthIndicator implements HealthIndicator {

    private final TaskRepository repository;

    public TaskEngineHealthIndicator(TaskRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        try {
            long pending = repository.countByStatus(TaskStatus.PENDING);
            long running = repository.countByStatus(TaskStatus.RUNNING);
            long dead = repository.countByStatus(TaskStatus.DEAD);
            return Health.up()
                    .withDetail("pending", pending)
                    .withDetail("running", running)
                    .withDetail("dead", dead)
                    .build();
        } catch (RuntimeException e) {
            return Health.down(e).build();
        }
    }
}
