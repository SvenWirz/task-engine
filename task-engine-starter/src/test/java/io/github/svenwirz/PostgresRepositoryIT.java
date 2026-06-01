package io.github.svenwirz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.svenwirz.api.EnqueueRequest;
import io.github.svenwirz.api.TaskService;
import io.github.svenwirz.config.TaskEngineProperties;
import io.github.svenwirz.core.RetentionJob;
import io.github.svenwirz.model.Task;
import io.github.svenwirz.model.TaskStatus;
import io.github.svenwirz.persistence.SqlDialect;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Integrationstests gegen ein echtes PostgreSQL (Testcontainers). Verifiziert die
 * PostgreSQL-spezifischen Pfade, die H2 nicht abbilden kann:
 * <ul>
 *   <li>Flyway-V1-Schema inkl. JSONB-Spalte, Trigger und partiellen Indizes (R1)</li>
 *   <li>{@code FOR UPDATE SKIP LOCKED}-Claiming, jede Zeile genau einmal (R4)</li>
 *   <li>Priorisierung (R14) und Scheduling (R18) im echten Claim-Statement</li>
 *   <li>R0-Transaktionsgarantie mit echtem {@code ?::jsonb}-Cast</li>
 *   <li>Partieller Unique-Index für Idempotenz, mehrere NULLs erlaubt (R16)</li>
 *   <li>Retention inkl. JSONB-Archivierung und Advisory-Lock (R26)</li>
 * </ul>
 * Worker-Seite ist hier aus; die Claim-/Status-Logik wird direkt über das Repository
 * getrieben, damit kein nebenläufiger Dispatcher die Testdaten verändert.
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "taskengine.enabled=false"
})
class PostgresRepositoryIT {

    @BeforeAll
    static void requireDatabase() {
        Assumptions.assumeTrue(PostgresSupport.available(),
                "Kein Docker/Testcontainers und kein it.postgres.baseurl — PG-ITs werden übersprungen");
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry, "repo_it");
    }

    @Autowired
    TaskRepository repository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TaskService taskService;
    @Autowired
    SqlDialect dialect;
    @Autowired
    TaskEngineProperties properties;
    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task");
        jdbc.update("DELETE FROM task_archive");
    }

    private Task newTask(String type, TaskStatus status, int priority, Instant availableAt) {
        Instant now = Instant.now();
        Task t = new Task();
        t.setId(UUID.randomUUID());
        t.setType(type);
        t.setPayload("{\"k\":1}");
        t.setStatus(status);
        t.setPriority(priority);
        t.setMaxAttempts(5);
        t.setAvailableAt(availableAt);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    @Test
    void runsOnRealPostgres() {
        assertThat(dialect.isPostgres()).isTrue();
    }

    @Test
    void flywayDeploysSchemaTriggerAndJsonbColumn() { // R1
        Long table = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name='task'", Long.class);
        assertThat(table).isEqualTo(1L);

        String payloadType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name='task' AND column_name='payload'", String.class);
        assertThat(payloadType).isEqualTo("jsonb");

        Long trigger = jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname='trg_task_notify'", Long.class);
        assertThat(trigger).isEqualTo(1L);
    }

    @Test
    void rollbackLeavesNoTaskWithJsonbCast() { // R0 auf PG inkl. ?::jsonb
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            taskService.enqueue("email", "{\"to\":\"a@b.de\"}");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count()).isZero();
    }

    @Test
    void enqueuePersistsJsonbPayload() { // R1 — JSONB-Roundtrip
        UUID id = taskService.enqueue("email", "{\"to\":\"a@b.de\",\"n\":5}");
        // JSONB-Operator funktioniert nur, wenn echt als jsonb gespeichert.
        String to = jdbc.queryForObject(
                "SELECT payload->>'to' FROM task WHERE id=?", String.class, id);
        assertThat(to).isEqualTo("a@b.de");
    }

    @Test
    void skipLockedClaimsHigherPriorityFirst() { // R14 — Auswahl nach Priorität
        Instant past = Instant.now().minusSeconds(1);
        repository.insert(newTask("t", TaskStatus.PENDING, 1, past));
        repository.insert(newTask("t", TaskStatus.PENDING, 9, past));
        repository.insert(newTask("t", TaskStatus.PENDING, 5, past));

        // Einzeln claimen: PostgreSQL garantiert die RETURNING-Reihenfolge nicht, wohl
        // aber, dass das LIMIT die höchstpriorisierte Zeile zuerst auswählt.
        int p1 = repository.claimBatch("n", 1, Instant.now()).get(0).getPriority();
        int p2 = repository.claimBatch("n", 1, Instant.now()).get(0).getPriority();
        int p3 = repository.claimBatch("n", 1, Instant.now()).get(0).getPriority();

        assertThat(List.of(p1, p2, p3)).containsExactly(9, 5, 1);
    }

    @Test
    void skipLockedDoesNotDoubleClaim() { // R4 — jede Zeile genau einem Knoten
        repository.insert(newTask("t", TaskStatus.PENDING, 0, Instant.now().minusSeconds(1)));

        List<Task> a = repository.claimBatch("node-a", 10, Instant.now());
        List<Task> b = repository.claimBatch("node-b", 10, Instant.now());

        assertThat(a).hasSize(1);
        assertThat(b).isEmpty();
    }

    @Test
    void concurrentClaimsNeverOverlap() throws Exception { // R4 — nebenläufig, SKIP LOCKED
        int total = 60;
        for (int i = 0; i < total; i++) {
            repository.insert(newTask("t", TaskStatus.PENDING, 0, Instant.now().minusSeconds(1)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        Set<UUID> claimedIds = ConcurrentHashMap.newKeySet();
        List<UUID> duplicates = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Callable<Void>> jobs = new java.util.ArrayList<>();
        for (int w = 0; w < 6; w++) {
            String node = "node-" + w;
            jobs.add(() -> {
                List<Task> claimed;
                do {
                    claimed = repository.claimBatch(node, 5, Instant.now());
                    for (Task t : claimed) {
                        if (!claimedIds.add(t.getId())) {
                            duplicates.add(t.getId());
                        }
                    }
                } while (!claimed.isEmpty());
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(jobs)) {
            f.get();
        }
        pool.shutdown();

        assertThat(duplicates).isEmpty();
        assertThat(claimedIds).hasSize(total);
    }

    @Test
    void doesNotClaimFutureScheduledTasks() { // R18
        repository.insert(newTask("t", TaskStatus.PENDING, 0, Instant.now().plusSeconds(3600)));
        repository.insert(newTask("t", TaskStatus.PENDING, 0, Instant.now().minusSeconds(1)));

        assertThat(repository.claimBatch("n", 10, Instant.now())).hasSize(1);
    }

    @Test
    void idempotencyUniqueIndexPreventsDuplicates() { // R16 — partieller Unique-Index
        EnqueueRequest a = EnqueueRequest.of("t", "{}").idempotencyKey("dedup").build();
        EnqueueRequest b = EnqueueRequest.of("t", "{}").idempotencyKey("dedup").build();

        UUID id1 = taskService.enqueue(a);
        UUID id2 = taskService.enqueue(b);

        assertThat(id1).isEqualTo(id2);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void multipleNullIdempotencyKeysAreAllowed() { // R16 — partieller Index ignoriert NULLs
        taskService.enqueue("t", "{}");
        taskService.enqueue("t", "{}");

        assertThat(count()).isEqualTo(2);
    }

    @Test
    void retentionDeletesAndArchivesOnPostgres() { // R26 inkl. Advisory-Lock
        Task old = newTask("t", TaskStatus.SUCCEEDED, 0, Instant.now());
        repository.insert(old);
        jdbc.update("UPDATE task SET updated_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), old.getId());

        // Archiv-Strategie über echten Advisory-Lock + JSONB-Insert in task_archive.
        properties.getRetention().setStrategy(TaskEngineProperties.Retention.Strategy.ARCHIVE);
        properties.getRetention().setSucceeded(java.time.Duration.ofDays(7));
        RetentionJob job = new RetentionJob(repository, jdbc, dialect, properties);
        job.runOnce();

        assertThat(repository.findById(old.getId())).isEmpty();
        Long archived = jdbc.queryForObject(
                "SELECT count(*) FROM task_archive WHERE id=?", Long.class, old.getId());
        assertThat(archived).isEqualTo(1L);
    }

    private long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM task", Long.class);
        return n == null ? 0 : n;
    }
}
