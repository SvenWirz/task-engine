package io.github.svenwirz.config;

import java.time.Duration;

/**
 * Backoff-/Retry-Parameter. Global oder pro Typ unter {@code taskengine.retry.<type>}.
 */
public class RetryProperties {

    /** Maximale Anzahl Versuche, bevor eine Task auf DEAD gesetzt wird. */
    private int maxAttempts = 5;

    /** Basis-Backoff für den ersten Retry. */
    private Duration baseBackoff = Duration.ofSeconds(5);

    /** Multiplikator für exponentielles Backoff. */
    private double multiplier = 2.0;

    /** Obergrenze für das Backoff. */
    private Duration maxBackoff = Duration.ofMinutes(10);

    /** Relativer Jitter (0..1), z. B. 0.2 = +/-20 % gegen Thundering-Herd. */
    private double jitter = 0.2;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBaseBackoff() {
        return baseBackoff;
    }

    public void setBaseBackoff(Duration baseBackoff) {
        this.baseBackoff = baseBackoff;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public double getJitter() {
        return jitter;
    }

    public void setJitter(double jitter) {
        this.jitter = jitter;
    }

    /** Tiefe Kopie als Ausgangspunkt für typ-spezifische Overrides. */
    public RetryProperties copy() {
        RetryProperties c = new RetryProperties();
        c.maxAttempts = this.maxAttempts;
        c.baseBackoff = this.baseBackoff;
        c.multiplier = this.multiplier;
        c.maxBackoff = this.maxBackoff;
        c.jitter = this.jitter;
        return c;
    }
}
