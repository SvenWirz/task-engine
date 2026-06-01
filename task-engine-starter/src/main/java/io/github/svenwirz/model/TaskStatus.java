package io.github.svenwirz.model;

/**
 * Lebenszyklus einer Task.
 *
 * <pre>
 *   PENDING ──claim──▶ RUNNING ──ok────▶ SUCCEEDED
 *                         │  └─fail,retry──▶ PENDING
 *                         │  └─fail,erschöpft──▶ DEAD
 *                         └─crash (reaper)──▶ PENDING
 *   PENDING/FAILED/DEAD ──cancel──▶ CANCELLED
 * </pre>
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    DEAD,
    CANCELLED
}
