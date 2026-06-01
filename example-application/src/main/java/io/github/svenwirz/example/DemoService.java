package io.github.svenwirz.example;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.svenwirz.api.TaskService;

/**
 * Demonstriert die R0-Garantie: Beide Methoden enqueuen eine Task innerhalb einer
 * Transaktion — die eine committet, die andere wirft danach eine Exception. Nur die
 * committete Task existiert anschließend; der Rollback hinterlässt keine Phantom-Task.
 */
@Service
public class DemoService {

    private final TaskService taskService;

    public DemoService(TaskService taskService) {
        this.taskService = taskService;
    }

    @Transactional
    public void enqueueWithinCommittedTransaction(String type, Object payload, int priority) {
        taskService.enqueue(type, payload, priority);
        // Transaktion committet normal → Task wird sichtbar und verarbeitet.
    }

    @Transactional
    public void enqueueThenRollback(String type, Object payload, int priority) {
        taskService.enqueue(type, payload, priority);
        // Geschäftsfehler NACH dem Enqueue → Rollback → keine Task entsteht (R0).
        throw new IllegalStateException("Simulierter Geschäftsfehler nach dem Enqueue");
    }
}
