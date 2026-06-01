# Task-Engine — Beispielanwendung

Eine kleine Spring-Boot-Anwendung, die den [`task-engine-spring-boot-starter`](../task-engine-starter)
demonstriert: Tasks einreihen, verarbeiten, Retry/DEAD beobachten, die R0-Transaktionsgarantie
ausprobieren und die Metriken in Grafana ansehen.

## Was demonstriert wird

| Aspekt | Wo |
|--------|----|
| Typisierter Payload (R25) | `EmailProcessor` (`TaskProcessor<EmailPayload>`) |
| Langläufig + Timeout (R22) | `ReportProcessor` (10s Timeout) |
| Retry/Backoff → DEAD (R6/R21) | `FlakyProcessor` (~50% Fehlerquote, eigene Retry-Policy) |
| Parallelitäts-Limit (R15) | `report: per-node: 2` in `application.yml` |
| **Transaktionale Konsistenz (R0)** | Button „Enqueue + Rollback" → es entsteht keine Task |
| REST-API (R13) | Starter-API unter `/taskengine/tasks` (in der UI verlinkt) |
| Health (R9) | `taskEngine`-Indikator unter `/actuator/health` |
| Metriken (R5) | `/actuator/prometheus` → Grafana-Dashboard |

## Voraussetzungen

- JDK 21, Maven
- Docker (für PostgreSQL, Prometheus, Grafana)

## Schnellstart

1. **Infrastruktur starten** (PostgreSQL, Prometheus, Grafana):

   ```bash
   cd example-application
   docker compose up -d
   ```

2. **Starter installieren und App starten** (vom Repo-Wurzelverzeichnis):

   ```bash
   mvn -pl task-engine-starter -DskipTests install
   mvn -pl example-application spring-boot:run
   ```

   > Die App nutzt PostgreSQL auf `localhost:5432` (siehe `application.yml`); Flyway legt
   > Schema, Trigger und Indizes des Starters automatisch an.

3. **UI öffnen:** <http://localhost:8080/>
   - Tasks einreihen (`email`/`report`/`flaky`), Priorität und Anzahl wählen.
   - „Enqueue + Rollback (R0)" drücken — die Live-Zähler zeigen, dass **keine** Task entsteht.
   - „Alle DEAD erneut versuchen" reiht erschöpfte Tasks neu ein.

4. **Grafana öffnen:** <http://localhost:3000/> (anonymer Admin-Zugang aktiviert)
   - Dashboard **„Task-Engine"** ist vorprovisioniert: Queue-Tiefe, Durchsatz,
     Erfolg/Fehlschläge, Verarbeitungs-Perzentile, Claims und Timeouts.
   - Prometheus (<http://localhost:9090/>) scrapt die App über `host.docker.internal:8080`.

## Endpunkte

| URL | Zweck |
|-----|-------|
| `/` | Demo-UI (Auto-Refresh) |
| `/taskengine/tasks` | REST-API des Starters (Liste/Filter/Retry/Cancel/Delete) |
| `/actuator/health` | Health inkl. `taskEngine`-Details |
| `/actuator/prometheus` | Prometheus-Scrape-Endpoint |

## Hinweise

- Läuft die App nicht auf Port 8080, passe `metrics_path`-Target in
  `monitoring/prometheus.yml` an.
- Die UI ist absichtlich minimal (server-gerendertes Thymeleaf, keine Build-Tools im
  Frontend), um den Fokus auf die Engine zu legen.
- Sicherheit: Die REST-API und die UI sind ungeschützt — nur für lokale Demos gedacht.
