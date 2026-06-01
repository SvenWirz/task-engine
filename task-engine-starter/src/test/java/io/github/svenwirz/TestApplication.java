package io.github.svenwirz;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimaler Anwendungskontext für die Tests des Starters. Aktiviert die
 * Auto-Konfiguration, ohne dass der Starter selbst eine Anwendung mitbringt.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {
}
