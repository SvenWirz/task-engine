package io.github.svenwirz.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Beschreibt eine zu erzeugende Task. Über den Builder zusammenstellbar; die
 * Convenience-Overloads von {@link TaskService} delegieren hierauf.
 */
public final class EnqueueRequest {

    private final String type;
    private final Object payload;
    private final int priority;
    private final Instant availableAt;
    private final String idempotencyKey;
    private final Integer maxAttempts;

    private EnqueueRequest(Builder b) {
        this.type = b.type;
        this.payload = b.payload;
        this.priority = b.priority;
        this.availableAt = b.availableAt;
        this.idempotencyKey = b.idempotencyKey;
        this.maxAttempts = b.maxAttempts;
    }

    public static Builder of(String type, Object payload) {
        return new Builder(type, payload);
    }

    public String type() {
        return type;
    }

    public Object payload() {
        return payload;
    }

    public int priority() {
        return priority;
    }

    /** Zeitpunkt, ab dem die Task claimbar ist; {@code null} = sofort (R18). */
    public Instant availableAt() {
        return availableAt;
    }

    /** Optionaler Dedup-Schlüssel (R16); {@code null} = keine Deduplizierung. */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /** Optionaler Override der maximalen Versuche; {@code null} = Policy-Default. */
    public Integer maxAttempts() {
        return maxAttempts;
    }

    public static final class Builder {
        private final String type;
        private final Object payload;
        private int priority = 0;
        private Instant availableAt;
        private String idempotencyKey;
        private Integer maxAttempts;

        private Builder(String type, Object payload) {
            this.type = type;
            this.payload = payload;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder availableAt(Instant availableAt) {
            this.availableAt = availableAt;
            return this;
        }

        public Builder availableAfter(Duration delay) {
            this.availableAt = Instant.now().plus(delay);
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder maxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public EnqueueRequest build() {
            return new EnqueueRequest(this);
        }
    }
}
