-- H2-Testschema (kein JSONB / kein Trigger — diese sind PG-spezifisch).
-- Payload als CLOB statt JSONB; Status-/Index-Semantik wie in V1.

CREATE TABLE IF NOT EXISTS task (
    id              UUID         PRIMARY KEY,
    type            VARCHAR(255) NOT NULL,
    payload         CLOB,
    status          VARCHAR(16)  NOT NULL,
    priority        INT          NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 5,
    available_at    TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    claimed_at      TIMESTAMP,
    claimed_by      VARCHAR(255),
    idempotency_key VARCHAR(255),
    last_error      CLOB,
    trace_id        VARCHAR(64),
    span_id         VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_task_idempotency_key ON task (idempotency_key);

CREATE TABLE IF NOT EXISTS task_archive (
    id              UUID         PRIMARY KEY,
    type            VARCHAR(255) NOT NULL,
    payload         CLOB,
    status          VARCHAR(16)  NOT NULL,
    priority        INT          NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 5,
    available_at    TIMESTAMP,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    claimed_at      TIMESTAMP,
    claimed_by      VARCHAR(255),
    idempotency_key VARCHAR(255),
    last_error      CLOB,
    trace_id        VARCHAR(64),
    span_id         VARCHAR(64)
);
