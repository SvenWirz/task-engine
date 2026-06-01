package io.github.svenwirz.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.svenwirz.core.EngineMetrics;
import io.github.svenwirz.metrics.MicrometerEngineMetrics;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Aktiviert Micrometer-Metriken (R5), sobald Micrometer und eine {@link MeterRegistry}
 * vorhanden sind (R7). Läuft <i>vor</i> der Kern-Konfiguration, damit die Micrometer-
 * Variante den No-Op-Fallback der {@link EngineMetrics} ersetzt, aber <i>nach</i> den
 * Micrometer-Auto-Konfigurationen von Spring Boot, damit die {@link MeterRegistry}
 * für {@code @ConditionalOnBean} bereits sichtbar ist.
 *
 * <p>{@code @ConditionalOnBean} prüft nur die {@link MeterRegistry}; {@link TaskRepository}
 * wird per Konstruktor injiziert (die Bean-Definition existiert unabhängig von der
 * Auswertungsreihenfolge).
 */
@AutoConfiguration(
        before = TaskEngineAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
        })
@ConditionalOnClass(MeterRegistry.class)
public class TaskEngineMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(EngineMetrics.class)
    public EngineMetrics micrometerEngineMetrics(MeterRegistry registry, TaskRepository repository) {
        return new MicrometerEngineMetrics(registry, repository);
    }
}
