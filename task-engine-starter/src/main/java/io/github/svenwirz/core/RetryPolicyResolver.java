package io.github.svenwirz.core;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import io.github.svenwirz.config.RetryProperties;
import io.github.svenwirz.config.TaskEngineProperties;

/**
 * Löst die effektive Retry-Policy pro Typ auf (R21):
 * {@code taskengine.retry.<type>.*} &gt; {@code TaskProcessor}-Default &gt; globaler Default.
 */
public class RetryPolicyResolver {

    private final TaskEngineProperties props;
    private final ProcessorRegistry registry;

    public RetryPolicyResolver(TaskEngineProperties props, ProcessorRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    public RetryProperties resolve(String type) {
        Map<String, RetryProperties> overrides = props.getRetry();
        if (overrides != null && overrides.containsKey(type)) {
            return overrides.get(type);
        }
        RetryProperties fromProcessor = registry.find(type)
                .map(p -> p.retryPolicy())
                .orElse(null);
        if (fromProcessor != null) {
            return fromProcessor;
        }
        return props.getDefaultRetry();
    }

    public int maxAttempts(String type) {
        return resolve(type).getMaxAttempts();
    }

    /**
     * Berechnet das nächste Backoff-Intervall für den {@code attempt}-ten Versuch
     * (1-basiert), exponentiell, gedeckelt, mit relativem Jitter.
     */
    public Duration backoffFor(String type, int attempt) {
        RetryProperties p = resolve(type);
        double base = p.getBaseBackoff().toMillis();
        double raw = base * Math.pow(p.getMultiplier(), Math.max(0, attempt - 1));
        double capped = Math.min(raw, p.getMaxBackoff().toMillis());
        double jitter = p.getJitter();
        if (jitter > 0) {
            double delta = capped * jitter;
            capped = capped - delta + ThreadLocalRandom.current().nextDouble(0, 2 * delta);
        }
        return Duration.ofMillis(Math.max(0, (long) capped));
    }
}
