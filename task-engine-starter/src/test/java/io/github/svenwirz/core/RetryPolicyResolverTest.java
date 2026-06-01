package io.github.svenwirz.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.svenwirz.api.TaskProcessor;
import io.github.svenwirz.config.RetryProperties;
import io.github.svenwirz.config.TaskEngineProperties;

/**
 * R21 — typ-spezifische Retry-Policy. Prüft die Auflösungsreihenfolge
 * (Property &gt; Processor-Default &gt; globaler Default) sowie exponentielles
 * Backoff mit Cap und Jitter-Grenzen.
 */
class RetryPolicyResolverTest {

    private RetryPolicyResolver resolver(TaskEngineProperties props, TaskProcessor<?>... processors) {
        ProcessorRegistry registry = new ProcessorRegistry(List.of(processors));
        return new RetryPolicyResolver(props, registry);
    }

    private TaskProcessor<String> processorWithPolicy(String type, RetryProperties policy) {
        return new TaskProcessor<>() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void process(String payload) {
            }

            @Override
            public RetryProperties retryPolicy() {
                return policy;
            }
        };
    }

    @Test
    void fallsBackToGlobalDefaultWhenNothingElseConfigured() {
        TaskEngineProperties props = new TaskEngineProperties();
        props.getDefaultRetry().setMaxAttempts(7);

        assertThat(resolver(props).maxAttempts("anything")).isEqualTo(7);
    }

    @Test
    void processorDefaultOverridesGlobalDefault() {
        TaskEngineProperties props = new TaskEngineProperties();
        props.getDefaultRetry().setMaxAttempts(5);
        RetryProperties processorPolicy = new RetryProperties();
        processorPolicy.setMaxAttempts(3);

        RetryPolicyResolver resolver = resolver(props, processorWithPolicy("email", processorPolicy));

        assertThat(resolver.maxAttempts("email")).isEqualTo(3);
    }

    @Test
    void propertyOverrideWinsOverProcessorDefault() {
        TaskEngineProperties props = new TaskEngineProperties();
        props.getDefaultRetry().setMaxAttempts(5);
        RetryProperties processorPolicy = new RetryProperties();
        processorPolicy.setMaxAttempts(3);
        RetryProperties propertyOverride = new RetryProperties();
        propertyOverride.setMaxAttempts(10);
        props.getRetry().put("email", propertyOverride);

        RetryPolicyResolver resolver = resolver(props, processorWithPolicy("email", processorPolicy));

        assertThat(resolver.maxAttempts("email")).isEqualTo(10);
    }

    @Test
    void backoffGrowsExponentiallyUntilCapped() {
        TaskEngineProperties props = new TaskEngineProperties();
        RetryProperties policy = props.getDefaultRetry();
        policy.setBaseBackoff(Duration.ofSeconds(1));
        policy.setMultiplier(2.0);
        policy.setMaxBackoff(Duration.ofSeconds(10));
        policy.setJitter(0.0); // deterministisch

        RetryPolicyResolver resolver = resolver(props);

        assertThat(resolver.backoffFor("t", 1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(resolver.backoffFor("t", 2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(resolver.backoffFor("t", 3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(resolver.backoffFor("t", 4)).isEqualTo(Duration.ofSeconds(8));
        // 16s wäre fällig, aber Cap bei 10s.
        assertThat(resolver.backoffFor("t", 5)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void jitterStaysWithinConfiguredBounds() {
        TaskEngineProperties props = new TaskEngineProperties();
        RetryProperties policy = props.getDefaultRetry();
        policy.setBaseBackoff(Duration.ofSeconds(10));
        policy.setMultiplier(1.0);
        policy.setMaxBackoff(Duration.ofSeconds(100));
        policy.setJitter(0.2); // +/-20 %

        RetryPolicyResolver resolver = resolver(props);

        for (int i = 0; i < 200; i++) {
            Duration d = resolver.backoffFor("t", 1);
            assertThat(d.toMillis()).isBetween(8_000L, 12_000L);
        }
    }
}
