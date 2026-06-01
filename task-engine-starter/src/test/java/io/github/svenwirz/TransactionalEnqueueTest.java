package io.github.svenwirz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.svenwirz.api.EnqueueRequest;
import io.github.svenwirz.api.TaskService;

/**
 * R0 — die primäre Garantie: Tasks existieren genau dann, wenn der aufrufende
 * Geschäftsprozess committet. Rollback oder Exception darf keine Phantom-Task hinterlassen.
 */
@SpringBootTest
class TransactionalEnqueueTest {

    @Autowired
    TaskService taskService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        jdbc.update("DELETE FROM task");
    }

    private long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM task", Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void rollbackOfBusinessTransactionLeavesNoTask() {
        assertThatThrownBy(() ->
                tx.executeWithoutResult(status -> {
                    // Enqueue innerhalb der Geschäftstransaktion …
                    taskService.enqueue("email", "{\"to\":\"a@b.de\"}");
                    // … die anschließend mit einer Exception abbricht.
                    throw new IllegalStateException("Geschäftsfehler nach Enqueue");
                }))
                .isInstanceOf(IllegalStateException.class);

        // R0: kein Phantom-Task nach Rollback.
        assertThat(count()).isZero();
    }

    @Test
    void explicitRollbackLeavesNoTask() {
        tx.executeWithoutResult(status -> {
            taskService.enqueue("email", "{\"to\":\"a@b.de\"}");
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
    }

    @Test
    void commitOfBusinessTransactionPersistsExactlyOneTask() {
        UUID id = tx.execute(status -> taskService.enqueue("email", "{\"to\":\"a@b.de\"}"));

        assertThat(id).isNotNull();
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM task WHERE id=?", String.class, id)).isEqualTo("PENDING");
    }

    @Test
    void enqueueWithoutAmbientTransactionIsAtomicOnItsOwn() {
        // Ohne aktive Transaktion kapselt Propagation.REQUIRED den Insert selbst.
        UUID id = taskService.enqueue("email", "{\"to\":\"a@b.de\"}");
        assertThat(id).isNotNull();
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void duplicateIdempotencyKeyCreatesOnlyOneTask() {
        EnqueueRequest first = EnqueueRequest.of("email", "{\"n\":1}").idempotencyKey("dedup-1").build();
        EnqueueRequest second = EnqueueRequest.of("email", "{\"n\":2}").idempotencyKey("dedup-1").build();

        UUID id1 = taskService.enqueue(first);
        UUID id2 = taskService.enqueue(second);

        assertThat(id1).isEqualTo(id2);
        assertThat(count()).isEqualTo(1);
    }
}
