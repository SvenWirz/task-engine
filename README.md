# Task-Engine Spring Boot Starter

[![CI](https://github.com/SvenWirz/task-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/SvenWirz/task-engine/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen.svg)](https://spring.io/projects/spring-boot)

## Zusammenfassung

Spring-Boot-Starter zur persistenten, asynchronen Abarbeitung von Tasks. Tasks entstehen **genau dann, wenn der aufrufende Geschäftsprozess committet** — ein Rollback oder eine Exception im Geschäftsprozess hinterlässt keine Phantom-Task (Transactional-Outbox-Garantie). Tasks werden von typ-spezifischen Processors parallel, cluster-sicher und mit geringer Latenz abgearbeitet. Tasks sind priorisierbar, planbar, idempotent deduplizierbar und in ihrer Parallelität pro Processor begrenzbar. Eine REST-API erlaubt die Verwaltung (insb. Retry). Tracing, Metriken und Actuator-Integration sind optional und aktivieren sich nur bei vorhandenem Classpath.

## Ziel / Kontext

Anwendungen brauchen einen einheitlichen Mechanismus, um Arbeit dauerhaft zu entkoppeln (enqueue jetzt, verarbeite später), der mehrere Instanzen verträgt.

**Das primäre Ziel ist transaktionale Konsistenz beim Enqueue:** `enqueue()` ist atomar mit dem aufrufenden Geschäftsprozess — kein Task entsteht, wenn der Prozess mit einer Exception abbricht oder die Transaktion rollt zurück. Ob das über eine DB-native Lösung (INSERT in gleicher Transaktion), ein Transactional-Outbox-Relay oder einen Message-Broker mit `afterCommit`-Publish realisiert wird, ist Implementierungsdetail und austauschbar.

---

## Funktionale Anforderungen — Bestand (umgesetzt)

| ID | Anforderung | Akzeptanzkriterium |
|----|-------------|--------------------|
| **R0** | **Transaktionale Konsistenz beim Enqueue (primär)** | `enqueue()` nimmt an der laufenden Geschäftstransaktion teil; Rollback oder Exception im aufrufenden Prozess verhindert die Entstehung der Task — keine Phantom-Task |
| R1 | Tasks werden persistiert | `task`-Tabelle mit JSONB-Payload; per Flyway oder Standalone-DDL anlegbar |
| R2 | Abarbeitung durch pluggable Processors | `TaskProcessor`-Beans über `type()` registriert und dispatcht |
| R3 | TraceId-Propagation beim Speichern + Wiederherstellen | Trace-/Span-IDs beim Enqueue gestempelt, bei Verarbeitung Span geöffnet |
| R4 | Cluster-sichere Abarbeitung | `SELECT … FOR UPDATE SKIP LOCKED`; jede Task von genau einem Knoten geclaimt |
| R5 | Erfassung geeigneter Metriken | Micrometer Counter, Perzentil-Timer, Queue-Tiefe-Gauges |
| R6 | Retry mit Backoff; erschöpfte Tasks → DEAD | Exponentielles Backoff; `DEAD` nach `maxAttempts` |
| R6b | Manuell per SQL eingefügte Tasks werden abgearbeitet | `NOTIFY` aus DB-Trigger, nicht aus App-Code |
| R7 | Tracing und Metriken optional (classpath-gesteuert) | `@ConditionalOnClass`/`@ConditionalOnBean`; No-Op-Fallbacks |
| R8 | Unbekannte Task-Typen scheitern sicher | Kein Processor → Task failed/dead |
| R9 | Actuator-Erweiterung falls vorhanden | `taskEngine` Health-Indicator + Info-Contributor |
| R10 | Jede Task läuft in eigener Transaktion | `TransactionTemplate` pro Task; Processor-Proxy erhalten |
| R11 | Geringe Dispatch-Latenz | `LISTEN/NOTIFY` weckt Dispatcher in ms; Fallback-Poll als Sicherheitsnetz |
| R12 | Crash-Recovery | Reaper requeued verwaiste `RUNNING`-Tasks |
| R13 | REST-API zur Verwaltung der Tasks | Auflisten/Filtern, Einzelabruf, manueller Retry (auch `DEAD`/`FAILED`), Abbrechen/Löschen, optional Neu-Anlegen |
| R14 | Priorisierung von Tasks | Priorität pro Task; Claiming bevorzugt höher priorisierte Tasks |
| R15 | Begrenzung paralleler Ausführungen pro Processor | Pro Typ konfigurierbares Limit — cluster-weit, pro Knoten oder beides kombiniert (striktere Grenze gilt) |
| R16 | Idempotenz / Deduplizierung | Optionaler `idempotency_key` (unique); doppeltes Enqueue desselben Keys erzeugt keine zweite Task |
| R17 | At-least-once-Semantik dokumentiert | Doku stellt klar, dass Processors idempotent sein müssen (Crash nach Verarbeitung, vor Commit → erneute Ausführung) |
| R18 | Verzögerte / geplante Ausführung | First-Class-API `enqueueAt(Instant)` / `enqueueAfter(Duration)` auf Basis von `available_at` |
| R21 | Konfigurierbare Retry-Policy pro Typ | Backoff (Basis, Faktor, Cap, Jitter) und `maxAttempts` pro Processor überschreibbar statt nur global |
| R22 | Timeout pro Task / Processor | Lang laufende Tasks nach konfigurierbarem Timeout abbrechen (Interrupt), Pool-Thread nicht dauerhaft blockieren |
| R25 | Typisierte Payload-(De)Serialisierung | Generisches `TaskProcessor<T>` mit Jackson-Mapping statt rohem JSON-String |
| R26 | Historie / Archivierung | `SUCCEEDED`-Tasks nach Frist archivieren/löschen, um Tabellenwachstum zu begrenzen |

---

## Nicht-funktionale Anforderungen

- **Skalierung:** horizontal, ohne Koordination zwischen Knoten (SKIP LOCKED). Knoten als reine Worker, reine Enqueuer oder beides (`taskengine.enabled`).
- **Parallelität:** dedizierter, Spring-verwalteter `ThreadPoolTaskExecutor` (`taskEngineExecutor`), Größe per `concurrency` konfigurierbar, überschreibbar.
- **Latenz:** Pickup typischerweise < 2 s nach Insert, getrieben durch NOTIFY statt Polling.
- **Infrastruktur:** Referenzimplementierung läuft über die bereits vorhandene PostgreSQL-Datenbank (kein zusätzliches Infra erforderlich); Broker-basierte Varianten sind möglich, sofern die R0-Garantie eingehalten wird.
- **Graceful Shutdown:** laufende Tasks werden bis `shutdown-seconds` zu Ende geführt.

---

## Architektur (Kurzüberblick)

- **Datenmodell:** eine `task`-Tabelle (Status-Maschine `PENDING → RUNNING → SUCCEEDED|FAILED|DEAD|CANCELLED`), partieller Index nur auf claimbare Zeilen.
- **Claiming:** Dispatcher-Thread holt Batches atomar via `FOR UPDATE SKIP LOCKED`, übergibt sie an den Thread-Pool; Semaphore begrenzt in-flight Work (Backpressure).
- **Wakeups:** dedizierte Connection auf `LISTEN task_new`; DB-Trigger feuert `pg_notify` bei jedem claimbaren Insert/Update — auch manuell. Fallback-Poll fängt verpasste Notifies ab.
- **Verarbeitung:** pro Task eigene Transaktion + Trace-Scope + MDC; Processor über Spring-Bean (Proxy) aufgerufen.
- **Recovery:** periodischer Reaper setzt verwaiste `RUNNING`-Tasks zurück.
- **Optionale Integrationen:** Tracing/Metriken/Context-Propagation/Actuator über Auto-Configuration, jeweils nur bei vorhandenem Classpath; sonst No-Op.

---

## Designvorgaben für die offenen Punkte

### R0 – Transaktionale Konsistenz beim Enqueue

Das ist die wichtigste Invariante des gesamten Systems. Implementierungsoptionen (alle erfüllen R0):

**Option A — DB-native (Referenzimplementierung):**
`TaskService.enqueue()` verwendet `propagation = REQUIRED` und schreibt den Task-Row in die laufende Transaktion des Aufrufers. Bei Rollback wird die INSERT-Zeile mitrolled zurück. Kein zusätzliches Infra.

**Option B — Transactional Outbox mit Relay:**
`enqueue()` schreibt in eine `task_outbox`-Staging-Tabelle innerhalb der Geschäftstransaktion. Ein separater Relay-Prozess liest committete Zeilen und überführt sie in die eigentliche `task`-Tabelle (oder einen Broker). Aufwändiger, aber Broker-kompatibel.

**Option C — Broker mit `afterCommit`-Callback:**
`enqueue()` registriert via `TransactionSynchronizationManager.registerSynchronization()` einen `afterCommit`-Hook, der die Nachricht erst nach erfolgreichem Commit an den Broker sendet. Bei Rollback läuft der Hook nicht.

**Empfehlung:** Option A als Default (zero Infra, atomar per DB-Semantik). Option C als Erweiterungspunkt für Broker-Anbindung. Option B nur wenn Broker-Latenz und garantierte Ordering nötig sind.

**Was keinesfalls erlaubt ist:** `enqueue()` in einer eigenen Transaktion (`REQUIRES_NEW`) aufrufen, während die Geschäftstransaktion noch offen ist — damit würde R0 gebrochen.

---

### R13 – REST-API

- Optionaler Controller, gegated via `@ConditionalOnClass` auf Spring MVC + Property `taskengine.api.enabled`.
- Endpunkte unter `taskengine.api.base-path` (Default `/taskengine`):
  - `GET /tasks` — Filter Status/Typ/Priorität + Pagination
  - `GET /tasks/{id}` — Einzelabruf inkl. `lastError`, `attempts`
  - `POST /tasks/{id}/retry` — `DEAD`/`FAILED` → `PENDING`, `available_at = now()`, optional `attempts`-Reset
  - `POST /tasks/{id}/cancel` — markiert als abgebrochen (Status `CANCELLED`)
  - `DELETE /tasks/{id}` — Hard-Delete
  - optional `POST /tasks` — manuelles Anlegen (deckt sich mit `TaskService.enqueue`)
  - Bulk-Retry für `DEAD` berücksichtigen
- Schreiboperationen über `TaskRepository` in eigener Transaktion; Retry löst über Trigger sofort NOTIFY aus.
- **Sicherheit:** Authn/Authz Sache der einbettenden Anwendung; Doku warnt vor ungeschützter Exposition.

### R14 – Priorisierung

- Spalte `priority INT NOT NULL DEFAULT 0` (höher = wichtiger).
- Claiming `ORDER BY priority DESC, available_at ASC`; partieller Index `(priority DESC, available_at) WHERE status='PENDING'`.
- `priority`-Parameter in `TaskService.enqueue(...)` und REST-API.

### R15 – Parallelitäts-Limit pro Processor (beides konfigurierbar)

- **Pro Knoten:** Semaphore je Typ im `WorkerManager`, vor Pool-Übergabe.
- **Cluster-weit:** Claim ermittelt pro Typ Restkontingent gegen DB (`COUNT(*) WHERE status='RUNNING' AND type=:type`, bzw. `LATERAL`-Subquery). Index `(type) WHERE status='RUNNING'`.
- **Kombination:** striktere effektive Grenze gilt.
- Konfiguration pro Typ:

```yaml
taskengine:
  processor-limits:
    email:   { per-node: 2, cluster-wide: 10 }
    report:  { cluster-wide: 1 }   # effektiv globaler Serializer für diesen Typ
```

  Optional Defaults am `TaskProcessor`; Properties haben Vorrang.

- **Hinweis:** Cluster-weite Variante kostet pro Claim-Runde eine zusätzliche Aggregat-Abfrage; Index empfohlen, Durchsatz unter Last beobachten.

### R16 – Idempotenz

- Spalte `idempotency_key VARCHAR UNIQUE` (nullable). `enqueue(..., idempotencyKey)` führt bei Konflikt zu No-Op (`ON CONFLICT DO NOTHING`, gibt existierende Task-ID zurück).
- Verträgt sich mit R13 (REST-Anlegen) und R18 (Scheduling).

### R17 – At-least-once-Vertrag

- Reine Doku-Anforderung: README-Abschnitt „Delivery-Garantien". Klarstellen: at-least-once, Processors müssen idempotent sein; Empfehlung, R16 oder fachliche Idempotenz zu nutzen.

### R18 – Scheduling

- `TaskService.enqueueAt(type, payload, Instant)` und `enqueueAfter(type, payload, Duration)` als First-Class-Methoden (setzen `available_at`). Bereits index-gestützt durch bestehenden Claim-Filter `available_at <= now()`.

### R21 – Retry-Policy pro Typ

- Policy-Objekt pro Typ: `baseBackoff`, `multiplier`, `maxBackoff`, `jitter`, `maxAttempts`. Auflösung: Property `taskengine.retry.<type>.*` > `TaskProcessor`-Default > globaler Default. Jitter (z. B. ±20 %) gegen Thundering-Herd nach Massen-Failures.

### R22 – Timeout

- Pro Typ `timeout`-Property. Ausführung in abbrechbarem Future; bei Überschreitung Interrupt + Behandlung als Failure (zählt auf `attempts`). Doku: Processors müssen Interrupts respektieren.

### R25 – Typisierte Payloads

- Generisches `TaskProcessor<T>` mit `Class<T> payloadType()` bzw. via Generics-Resolution; Jackson deserialisiert JSON → `T` vor `process(T)`. Roh-String-Variante bleibt für Rückwärtskompatibilität bestehen. **Berührt R2-Tests** — Anpassung einplanen.

### R26 – Archivierung

- Konfigurierbar `taskengine.retention.succeeded` (z. B. `7d`) und Strategie `delete` oder `archive` (Move in `task_archive`-Tabelle). Periodischer Job, cluster-safe (Advisory-Lock, nur ein Knoten pro Lauf). Default: löschen nach Frist, abschaltbar.

---

## Auswirkungen auf das Datenmodell (DDL-Änderungen, neue Flyway-Version `V2`)

- `priority INT NOT NULL DEFAULT 0` (R14)
- `idempotency_key VARCHAR UNIQUE` (R16)
- Status `CANCELLED` ergänzen (R13)
- Index `(priority DESC, available_at) WHERE status='PENDING'` (R14)
- Index `(type) WHERE status='RUNNING'` (R15)
- optional Tabelle `task_archive` (R26)
- Standalone-DDL entsprechend aktualisieren.

---

## Öffentliche API

- `TaskService.enqueue(type, payload[, priority][, idempotencyKey])`, `enqueueAt(...)`, `enqueueAfter(...)`.
- `TaskProcessor` / `TaskProcessor<T>` — optional Concurrency-, Retry-, Timeout-Defaults.
- REST-API (R13).
- Konfiguration unter `taskengine.*`: `concurrency`, `batch-size`, `poll-interval-ms`, `base-backoff-ms`, `stuck-seconds`, `processor-limits.*`, `retry.*`, `retention.*`, `api.*`.

---

## Out of Scope

- Wiederkehrende Cron-Tasks (R19), Dead-Letter-UI über REST hinaus (R20), Result-Output (R23), Lifecycle-Events (R24), Pause/Drain (R27), Multi-Tenancy (R28), Payload-Größenlimit (R29) — als mögliche Folge-Iterationen vermerkt, nicht in diesem Scope.
- Authn/Authz der REST-API (Verantwortung der einbettenden Anwendung).
- Andere Datenbanken als PostgreSQL für die Referenzimplementierung (NOTIFY und SKIP LOCKED sind PG-spezifisch; Broker-basierte Varianten sind möglich, liegen aber nicht im primären Scope).

---

## Definition of Done

- Schema via Flyway/DDL deploybar; Trigger + alle Indizes (R14/R15/R16) vorhanden.
- **R0:** Integrationstest belegt, dass `enqueue()` innerhalb einer rollenden Transaktion keine persistente Task erzeugt (kein Phantom-Task nach Rollback/Exception).
- R1–R12 weiterhin durch benannte Tests abgedeckt.
- R13: REST-Endpunkte inkl. Retry + Bulk-Retry implementiert und getestet (Controller- + Integrationstest).
- R14: höhere Priorität wird nachweislich zuerst geclaimt.
- R15: beide Limit-Ebenen getestet (pro-Knoten-Semaphore + cluster-weites DB-Kontingent mit mehreren simulierten Knoten).
- R16: doppeltes Enqueue mit gleichem Key erzeugt nur eine Task (Integrationstest).
- R17: README-Abschnitt „Delivery-Garantien" vorhanden.
- R18: Scheduling-API getestet (Task vor `available_at` wird nicht geclaimt).
- R21: typ-spezifische Retry-Policy inkl. Jitter getestet.
- R22: Timeout bricht hängende Task ab und wertet als Failure (Test).
- R25: typisierter Processor deserialisiert Payload korrekt; Roh-String-Variante weiter lauffähig.
- R26: abgelaufene `SUCCEEDED`-Tasks werden gelöscht/archiviert; Job cluster-safe.
- README mit Anforderungskatalog, Integrationsanleitung, DDL, Konfigurationsreferenz, REST-API-Doku (inkl. Sicherheitshinweis) und Delivery-Garantien.
