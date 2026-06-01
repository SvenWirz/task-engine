package io.github.svenwirz.persistence;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import io.github.svenwirz.model.Task;
import io.github.svenwirz.model.TaskStatus;

/**
 * JDBC-Zugriff auf die {@code task}-Tabelle. Bewusst auf {@link JdbcTemplate} statt
 * JPA, weil die cluster-sichere Claim-Logik fein steuerbares SQL braucht
 * ({@code FOR UPDATE SKIP LOCKED}, {@code RETURNING}).
 */
public class TaskRepository {

    private final JdbcTemplate jdbc;
    private final SqlDialect dialect;

    private static final String COLUMNS =
            "id, type, payload, status, priority, attempts, max_attempts, available_at, "
                    + "created_at, updated_at, claimed_at, claimed_by, idempotency_key, "
                    + "last_error, trace_id, span_id";

    public TaskRepository(JdbcTemplate jdbc, SqlDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    private final RowMapper<Task> mapper = (rs, rowNum) -> {
        Task t = new Task();
        t.setId((UUID) rs.getObject("id"));
        t.setType(rs.getString("type"));
        t.setPayload(rs.getString("payload"));
        t.setStatus(TaskStatus.valueOf(rs.getString("status")));
        t.setPriority(rs.getInt("priority"));
        t.setAttempts(rs.getInt("attempts"));
        t.setMaxAttempts(rs.getInt("max_attempts"));
        t.setAvailableAt(toInstant(rs.getTimestamp("available_at")));
        t.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        t.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        t.setClaimedAt(toInstant(rs.getTimestamp("claimed_at")));
        t.setClaimedBy(rs.getString("claimed_by"));
        t.setIdempotencyKey(rs.getString("idempotency_key"));
        t.setLastError(rs.getString("last_error"));
        t.setTraceId(rs.getString("trace_id"));
        t.setSpanId(rs.getString("span_id"));
        return t;
    };

    // ----------------------------------------------------------------- Enqueue (R0)

    /**
     * Fügt eine neue Task ein. Läuft in der Transaktion des Aufrufers (R0) —
     * der Aufrufer (z. B. {@code DefaultTaskService}) ist {@code @Transactional}.
     */
    public void insert(Task t) {
        String sql = "INSERT INTO task (" + COLUMNS + ") VALUES (?, ?, " + dialect.payloadPlaceholder()
                + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(sql,
                t.getId(),
                t.getType(),
                t.getPayload(),
                t.getStatus().name(),
                t.getPriority(),
                t.getAttempts(),
                t.getMaxAttempts(),
                Timestamp.from(t.getAvailableAt()),
                Timestamp.from(t.getCreatedAt()),
                Timestamp.from(t.getUpdatedAt()),
                t.getClaimedAt() == null ? null : Timestamp.from(t.getClaimedAt()),
                t.getClaimedBy(),
                t.getIdempotencyKey(),
                t.getLastError(),
                t.getTraceId(),
                t.getSpanId());
    }

    /**
     * Sucht eine existierende Task anhand des Idempotenz-Schlüssels (R16). Wird vom
     * Service genutzt, um nach einem Unique-Konflikt die bereits vorhandene ID
     * zurückzugeben.
     */
    public Optional<UUID> findIdByIdempotencyKey(String key) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM task WHERE idempotency_key = ?",
                (rs, n) -> (UUID) rs.getObject("id"), key);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** @return {@code true}, wenn der Insert wegen Idempotenz-Konflikt fehlschlug. */
    public boolean insertConflicts(Task t) {
        try {
            insert(t);
            return false;
        } catch (DuplicateKeyException e) {
            return true;
        }
    }

    // ----------------------------------------------------------------- Claiming (R4/R14)

    /**
     * Claimt atomar bis zu {@code limit} verfügbare Tasks für diesen Knoten.
     * PostgreSQL-Pfad nutzt {@code FOR UPDATE SKIP LOCKED} + {@code RETURNING}; jede
     * Zeile wird genau einem Knoten zugeteilt. Höhere Priorität zuerst (R14).
     */
    public List<Task> claimBatch(String nodeId, int limit, Instant now) {
        if (!dialect.isPostgres()) {
            return claimBatchPortable(nodeId, limit, now);
        }
        String sql = "UPDATE task SET status='RUNNING', claimed_by=?, claimed_at=?, updated_at=? "
                + "WHERE id IN ("
                + "  SELECT id FROM task"
                + "  WHERE status='PENDING' AND available_at <= ?"
                + "  ORDER BY priority DESC, available_at ASC"
                + "  LIMIT ?"
                + dialect.skipLocked()
                + ") RETURNING " + COLUMNS;
        Timestamp ts = Timestamp.from(now);
        return jdbc.query(sql, mapper, nodeId, ts, ts, ts, limit);
    }

    /** Nicht-PG-Fallback (nur für Tests): select + einzelne Updates, ohne SKIP LOCKED. */
    private List<Task> claimBatchPortable(String nodeId, int limit, Instant now) {
        Timestamp ts = Timestamp.from(now);
        List<Task> candidates = jdbc.query(
                "SELECT " + COLUMNS + " FROM task WHERE status='PENDING' AND available_at <= ? "
                        + "ORDER BY priority DESC, available_at ASC LIMIT ?",
                mapper, ts, limit);
        List<Task> claimed = new ArrayList<>();
        for (Task t : candidates) {
            int updated = jdbc.update(
                    "UPDATE task SET status='RUNNING', claimed_by=?, claimed_at=?, updated_at=? "
                            + "WHERE id=? AND status='PENDING'",
                    nodeId, ts, ts, t.getId());
            if (updated == 1) {
                t.setStatus(TaskStatus.RUNNING);
                t.setClaimedBy(nodeId);
                t.setClaimedAt(now);
                claimed.add(t);
            }
        }
        return claimed;
    }

    /**
     * Gibt eine geclaimte (RUNNING) Task wieder frei, ohne sie als Fehlversuch zu zählen
     * — z. B. wenn ein Parallelitäts-Limit (R15) die Ausführung gerade nicht zulässt.
     */
    public void requeueClaimed(UUID id, Instant now) {
        jdbc.update("UPDATE task SET status='PENDING', claimed_by=NULL, claimed_at=NULL, updated_at=? "
                + "WHERE id=? AND status='RUNNING'", Timestamp.from(now), id);
    }

    /** Anzahl aktuell laufender Tasks eines Typs — Basis für cluster-weites Limit (R15). */
    public int countRunningByType(String type) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task WHERE status='RUNNING' AND type=?", Integer.class, type);
        return n == null ? 0 : n;
    }

    // ----------------------------------------------------------------- Status-Übergänge

    public void markSucceeded(UUID id, Instant now) {
        jdbc.update("UPDATE task SET status='SUCCEEDED', updated_at=?, last_error=NULL WHERE id=?",
                Timestamp.from(now), id);
    }

    /** Fehlgeschlagener Versuch mit Retry: zurück auf PENDING, neues available_at. */
    public void markForRetry(UUID id, int attempts, Instant availableAt, String error, Instant now) {
        jdbc.update("UPDATE task SET status='PENDING', attempts=?, available_at=?, "
                        + "claimed_by=NULL, claimed_at=NULL, last_error=?, updated_at=? WHERE id=?",
                attempts, Timestamp.from(availableAt), truncate(error), Timestamp.from(now), id);
    }

    /** Versuche erschöpft → DEAD (R6). */
    public void markDead(UUID id, int attempts, String error, Instant now) {
        jdbc.update("UPDATE task SET status='DEAD', attempts=?, last_error=?, updated_at=? WHERE id=?",
                attempts, truncate(error), Timestamp.from(now), id);
    }

    public void markCancelled(UUID id, Instant now) {
        jdbc.update("UPDATE task SET status='CANCELLED', updated_at=? WHERE id=?",
                Timestamp.from(now), id);
    }

    /** REST-Retry (R13): DEAD/FAILED → PENDING, sofort verfügbar, optional attempts-Reset. */
    public int requeueForManualRetry(UUID id, boolean resetAttempts, Instant now) {
        String sql = "UPDATE task SET status='PENDING', available_at=?, claimed_by=NULL, "
                + "claimed_at=NULL, updated_at=?" + (resetAttempts ? ", attempts=0" : "")
                + " WHERE id=? AND status IN ('DEAD','FAILED')";
        Timestamp ts = Timestamp.from(now);
        return jdbc.update(sql, ts, ts, id);
    }

    /** Bulk-Retry aller DEAD-Tasks (R13). @return Anzahl requeueter Tasks. */
    public int requeueAllDead(boolean resetAttempts, Instant now) {
        String sql = "UPDATE task SET status='PENDING', available_at=?, claimed_by=NULL, "
                + "claimed_at=NULL, updated_at=?" + (resetAttempts ? ", attempts=0" : "")
                + " WHERE status='DEAD'";
        Timestamp ts = Timestamp.from(now);
        return jdbc.update(sql, ts, ts);
    }

    // ----------------------------------------------------------------- Recovery (R12)

    /** Requeued verwaiste RUNNING-Tasks, die seit {@code stuckBefore} hängen. */
    public int requeueStuck(Instant stuckBefore, Instant now) {
        return jdbc.update(
                "UPDATE task SET status='PENDING', claimed_by=NULL, claimed_at=NULL, updated_at=? "
                        + "WHERE status='RUNNING' AND claimed_at < ?",
                Timestamp.from(now), Timestamp.from(stuckBefore));
    }

    // ----------------------------------------------------------------- Retention (R26)

    public int deleteSucceededBefore(Instant before) {
        return jdbc.update("DELETE FROM task WHERE status='SUCCEEDED' AND updated_at < ?",
                Timestamp.from(before));
    }

    public int archiveSucceededBefore(Instant before) {
        Timestamp ts = Timestamp.from(before);
        int moved = jdbc.update(
                "INSERT INTO task_archive (" + COLUMNS + ") "
                        + "SELECT " + COLUMNS + " FROM task WHERE status='SUCCEEDED' AND updated_at < ?",
                ts);
        jdbc.update("DELETE FROM task WHERE status='SUCCEEDED' AND updated_at < ?", ts);
        return moved;
    }

    // ----------------------------------------------------------------- Lesen / REST (R13)

    public Optional<Task> findById(UUID id) {
        List<Task> rows = jdbc.query("SELECT " + COLUMNS + " FROM task WHERE id=?", mapper, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM task WHERE id=?", id) > 0;
    }

    /** Gefilterte, paginierte Liste. Null-Filter werden ignoriert. */
    public List<Task> list(TaskStatus status, String type, Integer priority, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM task WHERE 1=1");
        List<Object> args = new ArrayList<>();
        List<Integer> argTypes = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status=?");
            args.add(status.name());
            argTypes.add(Types.VARCHAR);
        }
        if (type != null) {
            sql.append(" AND type=?");
            args.add(type);
            argTypes.add(Types.VARCHAR);
        }
        if (priority != null) {
            sql.append(" AND priority=?");
            args.add(priority);
            argTypes.add(Types.INTEGER);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        argTypes.add(Types.INTEGER);
        args.add(offset);
        argTypes.add(Types.INTEGER);
        int[] types = argTypes.stream().mapToInt(Integer::intValue).toArray();
        return jdbc.query(sql.toString(), args.toArray(), types, mapper);
    }

    public long countByStatus(TaskStatus status) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE status=?", Long.class, status.name());
        return n == null ? 0 : n;
    }

    // ----------------------------------------------------------------- Helper

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 8000 ? s.substring(0, 8000) : s;
    }
}
