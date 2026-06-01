package io.github.svenwirz.rest;

import java.time.Instant;

/**
 * Request-Body für das optionale manuelle Anlegen einer Task (R13). {@code payload}
 * ist roher JSON, der unverändert gespeichert wird.
 */
public record CreateTaskRequest(
        String type,
        String payload,
        Integer priority,
        Instant availableAt,
        String idempotencyKey) {
}
