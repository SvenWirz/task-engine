package io.github.svenwirz.core;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.svenwirz.config.TaskEngineProperties;
import io.github.svenwirz.persistence.SqlDialect;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Archivierung / Löschung erfolgreicher Tasks (R26). Cluster-safe über einen
 * PostgreSQL-Advisory-Lock: pro Lauf arbeitet nur ein Knoten, die übrigen
 * überspringen. Auf nicht-PG-DBs (Tests) läuft der Job ohne Lock.
 */
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    /** Beliebige, stabile Lock-Kennung für den Retention-Lauf. */
    private static final long ADVISORY_LOCK_KEY = 0x7A5C_E461L;

    private final TaskRepository repository;
    private final JdbcTemplate jdbc;
    private final SqlDialect dialect;
    private final TaskEngineProperties properties;

    public RetentionJob(TaskRepository repository,
                        JdbcTemplate jdbc,
                        SqlDialect dialect,
                        TaskEngineProperties properties) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.properties = properties;
    }

    public void runOnce() {
        TaskEngineProperties.Retention cfg = properties.getRetention();
        if (!cfg.isEnabled()) {
            return;
        }
        if (!tryAcquireLock()) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(cfg.getSucceeded());
            int affected = switch (cfg.getStrategy()) {
                case DELETE -> repository.deleteSucceededBefore(cutoff);
                case ARCHIVE -> repository.archiveSucceededBefore(cutoff);
            };
            if (affected > 0) {
                log.info("Retention ({}) hat {} erfolgreiche Task(s) verarbeitet",
                        cfg.getStrategy(), affected);
            }
        } catch (RuntimeException e) {
            log.warn("Retention-Lauf fehlgeschlagen: {}", e.getMessage());
        } finally {
            releaseLock();
        }
    }

    private boolean tryAcquireLock() {
        if (!dialect.isPostgres()) {
            return true;
        }
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock() {
        if (!dialect.isPostgres()) {
            return;
        }
        try {
            jdbc.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        } catch (RuntimeException e) {
            log.debug("Advisory-Unlock fehlgeschlagen: {}", e.getMessage());
        }
    }
}
