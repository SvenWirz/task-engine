package io.github.svenwirz.rest;

import java.time.Instant;
import java.util.UUID;

import io.github.svenwirz.model.Task;

/**
 * Lesbare Außendarstellung einer Task für die REST-API (R13). Hält {@code lastError}
 * und {@code attempts} für die Fehlerdiagnose bereit.
 */
public record TaskView(
        UUID id,
        String type,
        String payload,
        String status,
        int priority,
        int attempts,
        int maxAttempts,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt,
        String lastError) {

    public static TaskView from(Task t) {
        return new TaskView(
                t.getId(),
                t.getType(),
                t.getPayload(),
                t.getStatus().name(),
                t.getPriority(),
                t.getAttempts(),
                t.getMaxAttempts(),
                t.getAvailableAt(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getLastError());
    }
}
