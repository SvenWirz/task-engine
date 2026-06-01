package io.github.svenwirz.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Beispielanwendung, die den task-engine-starter einbindet, ein paar Processors
 * registriert und eine schlanke Web-UI sowie Prometheus-Metriken bereitstellt.
 */
@SpringBootApplication
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
