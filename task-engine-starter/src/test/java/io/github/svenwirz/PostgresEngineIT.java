package io.github.svenwirz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.svenwirz.api.TaskProcessor;
import io.github.svenwirz.api.TaskService;
import io.github.svenwirz.core.Reaper;
import io.github.svenwirz.model.TaskStatus;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * End-to-End gegen echtes PostgreSQL mit aktivierter Worker-Seite.
 *
 * <p>Das Fallback-Poll-Intervall ist realistisch auf 5 s gesetzt (Sicherheitsnetz, R11).
 * Der Test {@link #enqueuedTaskIsProcessedViaNotify()} fordert die Verarbeitung in
 * <b>unter 3 s</b> — schneller als das Poll-Intervall — und beweist damit, dass das
 * {@code LISTEN/NOTIFY}-Wakeup (nicht der Poll) die geringe Latenz liefert. Die übrigen
 * Tests dürfen im seltenen Fall eines verpassten NOTIFY über den Poll erholt werden.
 * <ul>
 *   <li>R11: Trigger-getriebenes Wakeup nach Enqueue über die öffentliche API</li>
 *   <li>R6b: auch ein per rohem SQL eingefügter Task wird verarbeitet (NOTIFY aus dem
 *       DB-Trigger, nicht aus App-Code)</li>
 *   <li>R12: der Reaper requeued eine verwaiste RUNNING-Task; das Status-Update feuert
 *       erneut NOTIFY und die Task wird verarbeitet</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "taskengine.enabled=true",
        "taskengine.poll-interval=5s",
        "taskengine.concurrency=4"
})
class PostgresEngineIT {

    @BeforeAll
    static void requireDatabase() {
        Assumptions.assumeTrue(PostgresSupport.available(),
                "Kein Docker/Testcontainers und kein it.postgres.baseurl — PG-ITs werden übersprungen");
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry, "engine_it");
    }

    @Autowired
    TaskService taskService;
    @Autowired
    TaskRepository repository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Reaper reaper;

    @BeforeEach
    void clean() throws InterruptedException {
        jdbc.update("DELETE FROM task");
        // Kurz warten, damit der LISTEN-Loop die Verbindung sicher etabliert hat,
        // bevor wir ein NOTIFY auslösen (NOTIFY erreicht nur aktive Listener).
        Thread.sleep(800);
    }

    private TaskStatus status(UUID id) {
        return repository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void enqueuedTaskIsProcessedViaNotify() { // R11 — Latenz < Poll-Intervall ⇒ NOTIFY-getrieben
        UUID id = taskService.enqueue("ok", "{\"v\":1}");

        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.SUCCEEDED));
    }

    @Test
    void manuallyInsertedTaskIsProcessed() { // R6b — NOTIFY aus DB-Trigger
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task (id,type,payload,status,priority,attempts,max_attempts,"
                        + "available_at,created_at,updated_at) "
                        + "VALUES (?,?,?::jsonb,'PENDING',0,0,5,now(),now(),now())",
                id, "ok", "{\"manual\":true}");

        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.SUCCEEDED));
    }

    @Test
    void reaperRequeuesOrphanAndItGetsReprocessed() { // R12
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task (id,type,payload,status,priority,attempts,max_attempts,"
                        + "available_at,created_at,updated_at,claimed_at,claimed_by) "
                        + "VALUES (?,?,?::jsonb,'RUNNING',0,0,5,now(),now(),now(),?,'dead-node')",
                id, "ok", "{}", java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)));

        reaper.runOnce();

        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(TaskStatus.SUCCEEDED));
    }

    @TestConfiguration
    static class Config {
        @Bean
        TaskProcessor<String> okProcessor() {
            return new TaskProcessor<>() {
                @Override
                public String type() {
                    return "ok";
                }

                @Override
                public void process(String payload) {
                    // erfolgreich
                }
            };
        }
    }
}
