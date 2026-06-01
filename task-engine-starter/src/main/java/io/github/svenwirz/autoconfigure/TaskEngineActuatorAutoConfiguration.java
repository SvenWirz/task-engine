package io.github.svenwirz.autoconfigure;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.github.svenwirz.actuator.TaskEngineHealthIndicator;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Registriert den {@code taskEngine}-Health-Indikator (R9), wenn Actuator auf dem
 * Classpath ist (R7).
 */
@AutoConfiguration(after = TaskEngineAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
public class TaskEngineActuatorAutoConfiguration {

    @Bean(name = "taskEngineHealthIndicator")
    @ConditionalOnBean(TaskRepository.class)
    @ConditionalOnMissingBean(name = "taskEngineHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("taskEngine")
    public HealthIndicator taskEngineHealthIndicator(TaskRepository repository) {
        return new TaskEngineHealthIndicator(repository);
    }
}
