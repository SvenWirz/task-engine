-- Task-Engine Basis-Schema (PostgreSQL).
-- Kann per Flyway ausgeführt oder als Standalone-DDL angewendet werden.

CREATE TABLE IF NOT EXISTS task (
    id              UUID         PRIMARY KEY,
    type            VARCHAR(255) NOT NULL,
    payload         JSONB,
    status          VARCHAR(16)  NOT NULL,
    priority        INT          NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 5,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    claimed_by      VARCHAR(255),
    idempotency_key VARCHAR(255),
    last_error      TEXT,
    trace_id        VARCHAR(64),
    span_id         VARCHAR(64),
    CONSTRAINT task_status_chk
        CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','DEAD','CANCELLED'))
);

-- R16: doppeltes Enqueue desselben Schlüssels erzeugt keine zweite Task.
CREATE UNIQUE INDEX IF NOT EXISTS uq_task_idempotency_key
    ON task (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- R14: Claiming bevorzugt hohe Priorität, dann FIFO; partieller Index nur auf claimbare Zeilen.
CREATE INDEX IF NOT EXISTS ix_task_claimable
    ON task (priority DESC, available_at ASC) WHERE status = 'PENDING';

-- R15: cluster-weites Zählen laufender Tasks pro Typ.
CREATE INDEX IF NOT EXISTS ix_task_running_type
    ON task (type) WHERE status = 'RUNNING';

-- R26: schnelles Auffinden ablaufender erfolgreicher Tasks.
CREATE INDEX IF NOT EXISTS ix_task_succeeded_updated
    ON task (updated_at) WHERE status = 'SUCCEEDED';

-- Archiv-Tabelle (R26, optional genutzt bei Strategie ARCHIVE).
CREATE TABLE IF NOT EXISTS task_archive (
    id              UUID         PRIMARY KEY,
    type            VARCHAR(255) NOT NULL,
    payload         JSONB,
    status          VARCHAR(16)  NOT NULL,
    priority        INT          NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 5,
    available_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    claimed_at      TIMESTAMPTZ,
    claimed_by      VARCHAR(255),
    idempotency_key VARCHAR(255),
    last_error      TEXT,
    trace_id        VARCHAR(64),
    span_id         VARCHAR(64),
    archived_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- R6b / R11: Trigger feuert pg_notify, sobald eine claimbare Zeile entsteht.
-- Wichtig (R0): der Trigger läuft im selben Transaktionskontext; das NOTIFY wird
-- erst beim COMMIT der Transaktion zugestellt. Rollt die Transaktion zurück, gibt es
-- weder Zeile noch Notify — kein Phantom-Task, kein Phantom-Wakeup.
CREATE OR REPLACE FUNCTION task_notify() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'PENDING' THEN
        PERFORM pg_notify('task_new', NEW.type);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_task_notify ON task;
CREATE TRIGGER trg_task_notify
    AFTER INSERT OR UPDATE OF status, available_at ON task
    FOR EACH ROW EXECUTE FUNCTION task_notify();
