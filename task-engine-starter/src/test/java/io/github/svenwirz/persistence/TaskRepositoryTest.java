package io.github.svenwirz.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.svenwirz.model.Task;
import io.github.svenwirz.model.TaskStatus;

/**
 * Repository-Integrationstests gegen H2. Decken die DB-getragenen Anforderungen ab:
 * Persistenz (R1), Claiming-Reihenfolge nach Priorität (R4/R14), Scheduling (R18),
 * Crash-Recovery (R12), Retention (R26), REST-Schreiboperationen (R13) und das
 * Zählen laufender Tasks pro Typ (R15).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:te-repo;DB_CLOSE_DELAY=-1",
        "taskengine.enabled=false"
})
class TaskRepositoryTest {

    @Autowired
    TaskRepository repository;

    @Autowired
    JdbcTemplate jdbc;

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
        t.setPayload("{}");
        t.setStatus(status);
        t.setPriority(priority);
        t.setAttempts(0);
        t.setMaxAttempts(5);
        t.setAvailableAt(availableAt);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    @Test
    void persistsAndReadsBackTask() { // R1
        Task t = newTask("email", TaskStatus.PENDING, 0, Instant.now());
        t.setPayload("{\"to\":\"a@b.de\"}");
        repository.insert(t);

        Task loaded = repository.findById(t.getId()).orElseThrow();
        assertThat(loaded.getType()).isEqualTo("email");
        assertThat(loaded.getPayload()).contains("a@b.de");
        assertThat(loaded.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void claimsHigherPriorityFirst() { // R14
        Instant past = Instant.now().minusSeconds(1);
        repository.insert(newTask("t", TaskStatus.PENDING, 1, past));
        repository.insert(newTask("t", TaskStatus.PENDING, 9, past));
        repository.insert(newTask("t", TaskStatus.PENDING, 5, past));

        List<Task> claimed = repository.claimBatch("node-1", 10, Instant.now());

        assertThat(claimed).extracting(Task::getPriority).containsExactly(9, 5, 1);
        assertThat(claimed).allMatch(t -> t.getStatus() == TaskStatus.RUNNING);
        assertThat(claimed).allMatch(t -> "node-1".equals(t.getClaimedBy()));
    }

    @Test
    void doesNotClaimTasksScheduledForTheFuture() { // R18
        repository.insert(newTask("t", TaskStatus.PENDING, 0, Instant.now().plusSeconds(3600)));
        repository.insert(newTask("t", TaskStatus.PENDING, 0, Instant.now().minusSeconds(1)));

        List<Task> claimed = repository.claimBatch("node-1", 10, Instant.now());

        assertThat(claimed).hasSize(1);
    }

    @Test
    void claimedTaskIsNotClaimedAgain() { // R4 — genau ein Knoten
        repository.insert(newTask("t", TaskStatus.PENDING, 0, Instant.now().minusSeconds(1)));

        List<Task> first = repository.claimBatch("node-1", 10, Instant.now());
        List<Task> second = repository.claimBatch("node-2", 10, Instant.now());

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    @Test
    void reaperRequeuesOrphanedRunningTasks() { // R12
        Task stuck = newTask("t", TaskStatus.RUNNING, 0, Instant.now().minusSeconds(1));
        repository.insert(stuck);
        // claimed_at künstlich in die Vergangenheit setzen.
        jdbc.update("UPDATE task SET claimed_at=?, claimed_by='dead-node' WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), stuck.getId());

        int requeued = repository.requeueStuck(Instant.now().minus(5, ChronoUnit.MINUTES), Instant.now());

        assertThat(requeued).isEqualTo(1);
        assertThat(repository.findById(stuck.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void doesNotRequeueRecentlyClaimedRunningTasks() { // R12 — Gegenprobe
        Task fresh = newTask("t", TaskStatus.RUNNING, 0, Instant.now());
        repository.insert(fresh);
        jdbc.update("UPDATE task SET claimed_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now()), fresh.getId());

        int requeued = repository.requeueStuck(Instant.now().minus(5, ChronoUnit.MINUTES), Instant.now());

        assertThat(requeued).isZero();
    }

    @Test
    void deletesExpiredSucceededTasks() { // R26 — Strategie DELETE
        Task old = newTask("t", TaskStatus.SUCCEEDED, 0, Instant.now());
        repository.insert(old);
        jdbc.update("UPDATE task SET updated_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), old.getId());

        int deleted = repository.deleteSucceededBefore(Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById(old.getId())).isEmpty();
    }

    @Test
    void archivesExpiredSucceededTasks() { // R26 — Strategie ARCHIVE
        Task old = newTask("t", TaskStatus.SUCCEEDED, 0, Instant.now());
        repository.insert(old);
        jdbc.update("UPDATE task SET updated_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), old.getId());

        int archived = repository.archiveSucceededBefore(Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(archived).isEqualTo(1);
        assertThat(repository.findById(old.getId())).isEmpty();
        Long inArchive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_archive WHERE id=?", Long.class, old.getId());
        assertThat(inArchive).isEqualTo(1L);
    }

    @Test
    void manualRetryMovesDeadBackToPending() { // R13
        Task dead = newTask("t", TaskStatus.DEAD, 0, Instant.now());
        dead.setAttempts(5);
        repository.insert(dead);

        int updated = repository.requeueForManualRetry(dead.getId(), true, Instant.now());

        assertThat(updated).isEqualTo(1);
        Task after = repository.findById(dead.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(after.getAttempts()).isZero();
    }

    @Test
    void bulkRetryRequeuesAllDead() { // R13 — Bulk-Retry
        repository.insert(newTask("t", TaskStatus.DEAD, 0, Instant.now()));
        repository.insert(newTask("t", TaskStatus.DEAD, 0, Instant.now()));
        repository.insert(newTask("t", TaskStatus.SUCCEEDED, 0, Instant.now()));

        int requeued = repository.requeueAllDead(false, Instant.now());

        assertThat(requeued).isEqualTo(2);
        assertThat(repository.countByStatus(TaskStatus.PENDING)).isEqualTo(2);
    }

    @Test
    void countsRunningTasksPerType() { // R15
        repository.insert(newTask("report", TaskStatus.RUNNING, 0, Instant.now()));
        repository.insert(newTask("report", TaskStatus.RUNNING, 0, Instant.now()));
        repository.insert(newTask("email", TaskStatus.RUNNING, 0, Instant.now()));

        assertThat(repository.countRunningByType("report")).isEqualTo(2);
        assertThat(repository.countRunningByType("email")).isEqualTo(1);
    }

    @Test
    void listFiltersByStatusAndType() { // R13 — Filter
        repository.insert(newTask("email", TaskStatus.PENDING, 0, Instant.now()));
        repository.insert(newTask("report", TaskStatus.PENDING, 0, Instant.now()));
        repository.insert(newTask("email", TaskStatus.DEAD, 0, Instant.now()));

        List<Task> emailsPending = repository.list(TaskStatus.PENDING, "email", null, 50, 0);

        assertThat(emailsPending).hasSize(1);
        assertThat(emailsPending.get(0).getType()).isEqualTo("email");
        assertThat(emailsPending.get(0).getStatus()).isEqualTo(TaskStatus.PENDING);
    }
}
