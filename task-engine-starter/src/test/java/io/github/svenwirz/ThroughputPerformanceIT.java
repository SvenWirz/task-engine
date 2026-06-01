package io.github.svenwirz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.svenwirz.api.TaskProcessor;

/**
 * Wiederholbarer Durchsatz-Benchmark der Engine gegen echtes PostgreSQL — also den
 * Produktionspfad (Claim per {@code SKIP LOCKED}, Wakeup per {@code LISTEN/NOTIFY}).
 *
 * <h2>Was gemessen wird</h2>
 * Pro Runde wird ein <b>Backlog</b> von {@code N} {@code PENDING}-Tasks in <b>einer</b>
 * Transaktion eingefügt. Da der DB-Trigger das {@code NOTIFY} erst beim COMMIT zustellt
 * (siehe {@code V1__task_engine.sql}), wird der gesamte Backlog erst nach dem Commit
 * sichtbar — genau ein Wakeup-Punkt. Anschließend wird die <b>Drain-Zeit</b> gemessen:
 * vom Commit bis alle Tasks {@code SUCCEEDED} sind. Daraus folgt der Indikator
 * <b>Ausführungen/Minute</b> der Worker-Seite.
 *
 * <p>Der {@link TaskProcessor} tut bewusst nichts: gemessen wird der reine Engine-Overhead
 * (Claim-Runde, Dispatch in den Pool, Status-Update {@code RUNNING → SUCCEEDED} samt
 * Folge-NOTIFY), nicht die Laufzeit einer Nutzlast. Das Einfügen des Backlogs wird separat
 * ausgewiesen und geht <b>nicht</b> in den Durchsatz ein.
 *
 * <h2>Warum das wiederholbar ist</h2>
 * <ul>
 *   <li>Eine <b>Warmup-Runde</b> (nicht gewertet) deckt JIT, Connection-Pool-Aufbau und
 *       Plan-Caching der DB ab.</li>
 *   <li>Mehrere <b>gewertete Runden</b> liefern Median und Best statt eines Einzelwerts —
 *       robust gegen Ausreißer.</li>
 *   <li>Fixe, über System-Properties steuerbare Last- und Engine-Parameter, sodass zwei
 *       Läufe vergleichbar sind.</li>
 * </ul>
 *
 * <h2>Ausführen</h2>
 * Der Test ist <b>opt-in</b> (läuft nicht im normalen Build mit) und braucht Docker/Testcontainers
 * (oder eine externe DB via {@code -Dit.postgres.baseurl=...}, siehe {@link PostgresSupport}):
 * <pre>{@code
 *   mvn -pl task-engine-starter test -Dtest=ThroughputPerformanceIT -Dte.perf=true
 *   # mit eigener Last:
 *   mvn -pl task-engine-starter test -Dtest=ThroughputPerformanceIT -Dte.perf=true \
 *       -Dte.perf.tasks=20000 -Dte.perf.rounds=5 -Dte.perf.concurrency=16 -Dte.perf.batchSize=128
 * }</pre>
 *
 * <p>Steuerbare System-Properties (mit Defaults):
 * <ul>
 *   <li>{@code te.perf.tasks=5000} — Backlog-Größe pro Runde</li>
 *   <li>{@code te.perf.rounds=3} — gewertete Runden</li>
 *   <li>{@code te.perf.warmup=1} — Warmup-Runden (nicht gewertet)</li>
 *   <li>{@code te.perf.concurrency=8} — Worker-Threads</li>
 *   <li>{@code te.perf.batchSize=64} — Claim-Batch-Größe</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "te.perf", matches = "true",
        disabledReason = "Perf-Benchmark ist opt-in: mit -Dte.perf=true ausführen")
@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "taskengine.enabled=true",
        // NOTIFY treibt den Durchsatz; der Poll ist nur Sicherheitsnetz und soll nicht dominieren.
        "taskengine.poll-interval=250ms",
        // Reaper/Retention dürfen den Benchmark nicht stören.
        "taskengine.reaper-interval=1h",
        "taskengine.retention.enabled=false"
})
class ThroughputPerformanceIT {

    private static final int TASKS = intProp("te.perf.tasks", 5000);
    private static final int ROUNDS = intProp("te.perf.rounds", 3);
    private static final int WARMUP = intProp("te.perf.warmup", 1);
    private static final int INSERT_BATCH = 500;

    @BeforeAll
    static void requireDatabase() {
        Assumptions.assumeTrue(PostgresSupport.available(),
                "Kein Docker/Testcontainers und kein it.postgres.baseurl — Perf-Benchmark wird übersprungen");
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry, "engine_perf");
        registry.add("taskengine.concurrency", () -> intProp("te.perf.concurrency", 8));
        registry.add("taskengine.batch-size", () -> intProp("te.perf.batchSize", 64));
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws InterruptedException {
        tx = new TransactionTemplate(txManager);
        jdbc.update("DELETE FROM task");
        // Dem LISTEN-Loop kurz Zeit geben, die Verbindung zu etablieren, bevor NOTIFY feuert.
        Thread.sleep(500);
    }

    @Test
    void measuresExecutionsPerMinute() {
        int concurrency = intProp("te.perf.concurrency", 8);
        int batchSize = intProp("te.perf.batchSize", 64);

        System.out.printf(Locale.ROOT,
                "%n=== Task-Engine Durchsatz-Benchmark ===%n"
                        + "backlog/Runde=%d  warmup=%d  gewertete Runden=%d  concurrency=%d  batchSize=%d%n%n",
                TASKS, WARMUP, ROUNDS, concurrency, batchSize);

        for (int i = 0; i < WARMUP; i++) {
            Round r = runRound();
            System.out.printf(Locale.ROOT, "warmup    : %s%n", r);
        }

        List<Double> perMinute = new ArrayList<>(ROUNDS);
        for (int i = 0; i < ROUNDS; i++) {
            Round r = runRound();
            perMinute.add(r.executionsPerMinute());
            System.out.printf(Locale.ROOT, "Runde %-3d : %s%n", i + 1, r);
        }

        double median = median(perMinute);
        double best = perMinute.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        System.out.printf(Locale.ROOT,
                "%n--- Ergebnis (Verarbeitungsdurchsatz, ohne Einfügen) ---%n"
                        + "Median : %,.0f Ausführungen/min%n"
                        + "Best   : %,.0f Ausführungen/min%n"
                        + "========================================================%n%n",
                median, best);

        // Korrektheit ist hart; der Durchsatz-Floor ist bewusst niedrig (nur ein Smoke-Test
        // gegen Total-Regressionen), damit der Benchmark nicht durch Last der Maschine flakt.
        assertThat(median).as("Median-Durchsatz (Ausführungen/min)").isGreaterThan(60.0);
    }

    /**
     * Eine Runde: Backlog in einer Transaktion committen (Sichtbarkeit + ein NOTIFY),
     * dann die Drain-Zeit bis zur vollständigen Verarbeitung messen.
     */
    private Round runRound() {
        jdbc.update("DELETE FROM task");

        long loadStart = System.nanoTime();
        insertBacklog();
        long drainStart = System.nanoTime(); // unmittelbar nach COMMIT der Insert-Transaktion

        // Großzügiges, an die Last gekoppeltes Timeout: selbst bei sehr langsamer Maschine
        // soll der Lauf abschließen statt fälschlich zu scheitern.
        long timeoutSeconds = Math.max(60, TASKS / 5L);
        await().atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(25, TimeUnit.MILLISECONDS)
                .until(() -> succeededCount() == TASKS);
        long drainEnd = System.nanoTime();

        return new Round(TASKS,
                (drainStart - loadStart) / 1_000_000.0,
                (drainEnd - drainStart) / 1_000_000.0);
    }

    /** Fügt den kompletten Backlog in genau einer Transaktion ein → ein Commit, ein NOTIFY. */
    private void insertBacklog() {
        tx.executeWithoutResult(status -> {
            for (int offset = 0; offset < TASKS; offset += INSERT_BATCH) {
                final int base = offset;
                final int chunk = Math.min(INSERT_BATCH, TASKS - offset);
                jdbc.batchUpdate(
                        "INSERT INTO task (id,type,payload,status,priority,attempts,max_attempts,"
                                + "available_at,created_at,updated_at) "
                                + "VALUES (?,?,?::jsonb,'PENDING',0,0,5,now(),now(),now())",
                        new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                                ps.setObject(1, UUID.randomUUID());
                                ps.setString(2, "perf-noop");
                                ps.setString(3, "{\"i\":" + (base + i) + "}");
                            }

                            @Override
                            public int getBatchSize() {
                                return chunk;
                            }
                        });
            }
        });
    }

    private long succeededCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task WHERE type='perf-noop' AND status='SUCCEEDED'", Long.class);
        return n == null ? 0 : n;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static int intProp(String name, int defaultValue) {
        String v = System.getProperty(name);
        return v == null || v.isBlank() ? defaultValue : Integer.parseInt(v.trim());
    }

    /** Ergebnis einer Runde: Einfüge-Zeit (informativ) und Drain-Zeit (durchsatzbestimmend). */
    private record Round(int tasks, double loadMillis, double drainMillis) {

        double executionsPerMinute() {
            return drainMillis <= 0 ? 0 : tasks / (drainMillis / 1000.0) * 60.0;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "einfügen=%,.0f ms  drain=%,.0f ms  → %,.0f Ausführungen/min",
                    loadMillis, drainMillis, executionsPerMinute());
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        TaskProcessor<String> perfNoopProcessor() {
            return new TaskProcessor<>() {
                @Override
                public String type() {
                    return "perf-noop";
                }

                @Override
                public void process(String payload) {
                    // Bewusst leer: misst den reinen Engine-Overhead, nicht die Nutzlast.
                }
            };
        }
    }
}
