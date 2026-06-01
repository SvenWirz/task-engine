package io.github.svenwirz.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistente Repräsentation einer Task-Zeile.
 *
 * <p>Bewusst als veränderbares Datenobjekt gehalten, damit das Repository
 * Zeilen unkompliziert mappen kann. Anwendungscode sollte Tasks über
 * {@link io.github.svenwirz.api.TaskService} erzeugen statt direkt zu instanziieren.
 */
public class Task {

    private UUID id;
    private String type;
    private String payload;
    private TaskStatus status;
    private int priority;
    private int attempts;
    private int maxAttempts;
    private Instant availableAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant claimedAt;
    private String claimedBy;
    private String idempotencyKey;
    private String lastError;
    private String traceId;
    private String spanId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(Instant availableAt) {
        this.availableAt = availableAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    @Override
    public String toString() {
        return "Task{id=" + id + ", type='" + type + "', status=" + status
                + ", priority=" + priority + ", attempts=" + attempts + "/" + maxAttempts + "}";
    }
}
