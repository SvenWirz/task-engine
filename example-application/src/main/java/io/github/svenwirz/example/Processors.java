package io.github.svenwirz.example;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.svenwirz.api.TaskProcessor;
import io.github.svenwirz.config.RetryProperties;

/**
 * Die Demo-Processors. Jeder zeigt einen anderen Aspekt der Engine:
 * <ul>
 *   <li>{@code email} — typisierter Payload (R25), kurze Verarbeitung</li>
 *   <li>{@code report} — länger laufend, eigener Timeout (R22)</li>
 *   <li>{@code flaky} — schlägt zufällig fehl, um Retry/Backoff/DEAD und die
 *       Fehler-Metriken sichtbar zu machen (R6/R21)</li>
 * </ul>
 */
@Configuration
public class Processors {

    private static final Logger log = LoggerFactory.getLogger(Processors.class);

    @Bean
    public TaskProcessor<EmailPayload> emailProcessor() {
        return new TaskProcessor<>() {
            @Override
            public String type() {
                return "email";
            }

            @Override
            public void process(EmailPayload payload) throws InterruptedException {
                log.info("Sende E-Mail an {} (Betreff: {})", payload.to(), payload.subject());
                Thread.sleep(ThreadLocalRandom.current().nextLong(200, 800));
            }
        };
    }

    @Bean
    public TaskProcessor<String> reportProcessor() {
        return new TaskProcessor<>() {
            @Override
            public String type() {
                return "report";
            }

            @Override
            public void process(String payload) throws InterruptedException {
                log.info("Erzeuge Report: {}", payload);
                Thread.sleep(ThreadLocalRandom.current().nextLong(1500, 3500));
            }

            @Override
            public Duration timeout() {
                return Duration.ofSeconds(10);
            }
        };
    }

    @Bean
    public TaskProcessor<String> flakyProcessor() {
        return new TaskProcessor<>() {
            @Override
            public String type() {
                return "flaky";
            }

            @Override
            public void process(String payload) {
                if (ThreadLocalRandom.current().nextDouble() < 0.5) {
                    throw new IllegalStateException("Zufälliger Fehler bei der Verarbeitung");
                }
                log.info("Flaky-Task erfolgreich: {}", payload);
            }

            @Override
            public RetryProperties retryPolicy() {
                RetryProperties policy = new RetryProperties();
                policy.setMaxAttempts(4);
                policy.setBaseBackoff(Duration.ofSeconds(2));
                policy.setMultiplier(2.0);
                policy.setJitter(0.2);
                return policy;
            }
        };
    }
}
