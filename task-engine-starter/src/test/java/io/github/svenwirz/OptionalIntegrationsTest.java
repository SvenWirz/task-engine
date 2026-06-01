package io.github.svenwirz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.github.svenwirz.actuator.TaskEngineHealthIndicator;
import io.github.svenwirz.api.TaskService;
import io.github.svenwirz.autoconfigure.TaskEngineActuatorAutoConfiguration;
import io.github.svenwirz.autoconfigure.TaskEngineAutoConfiguration;
import io.github.svenwirz.autoconfigure.TaskEngineMetricsAutoConfiguration;
import io.github.svenwirz.autoconfigure.TaskEngineWebAutoConfiguration;
import io.github.svenwirz.core.EngineMetrics;
import io.github.svenwirz.core.TaskDispatcher;
import io.github.svenwirz.metrics.MicrometerEngineMetrics;
import io.github.svenwirz.rest.TaskController;

/**
 * R7 — optionale Integrationen sind classpath-/property-gesteuert mit No-Op-Fallbacks.
 * Geprüft über {@link ApplicationContextRunner}, ohne die Worker-Threads zu starten
 * ({@code taskengine.enabled=false}).
 */
class OptionalIntegrationsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TaskEngineMetricsAutoConfiguration.class,
                    TaskEngineAutoConfiguration.class,
                    TaskEngineActuatorAutoConfiguration.class,
                    TaskEngineWebAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "taskengine.enabled=false");

    @Test
    void enqueueSideIsAlwaysAvailable() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(TaskService.class));
    }

    @Test
    void workerBeansAbsentWhenDisabled() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(TaskDispatcher.class);
            assertThat(ctx).hasSingleBean(TaskService.class); // Enqueue bleibt nutzbar
        });
    }

    @Test
    void restControllerAbsentByDefault() { // R13/R7 — API standardmäßig aus
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TaskController.class));
    }

    @Test
    void restControllerPresentWhenApiEnabled() {
        runner.withPropertyValues("taskengine.api.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(TaskController.class));
    }

    @Test
    void metricsFallBackToNoOpWhenMicrometerAbsent() { // R7 — No-Op-Fallback
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EngineMetrics.class);
                    assertThat(ctx.getBean(EngineMetrics.class)).isSameAs(EngineMetrics.NOOP);
                });
    }

    @Test
    void micrometerMetricsUsedWhenRegistryPresent() { // R5/R7
        runner.withBean(SimpleMeterRegistry.class)
                .run(ctx -> assertThat(ctx.getBean(EngineMetrics.class))
                        .isInstanceOf(MicrometerEngineMetrics.class));
    }

    @Test
    void actuatorHealthIndicatorPresentWhenActuatorOnClasspath() { // R9
        runner.run(ctx -> assertThat(ctx).hasSingleBean(TaskEngineHealthIndicator.class));
    }
}
