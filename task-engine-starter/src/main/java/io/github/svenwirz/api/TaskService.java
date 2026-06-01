package io.github.svenwirz.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Öffentliche Enqueue-API.
 *
 * <h2>R0 — Transaktionale Konsistenz (primäre Garantie)</h2>
 * Alle {@code enqueue*}-Methoden nehmen an einer bereits laufenden Transaktion des
 * Aufrufers teil ({@code Propagation.REQUIRED}). Konkret bedeutet das:
 * <ul>
 *   <li>Rollt die Geschäftstransaktion zurück (Exception, manuelles Rollback), wird
 *       die Task <b>nicht</b> persistiert — keine Phantom-Task.</li>
 *   <li>Erst mit dem Commit der Geschäftstransaktion wird die Task sichtbar und
 *       (per Trigger-NOTIFY) zur Verarbeitung freigegeben.</li>
 *   <li>Wird ohne aktive Transaktion aufgerufen, kapselt {@code REQUIRED} den INSERT
 *       in eine eigene kurze Transaktion — der Enqueue bleibt atomar.</li>
 * </ul>
 * Es wird bewusst <b>nie</b> {@code REQUIRES_NEW} verwendet; das würde R0 brechen.
 */
public interface TaskService {

    /** Enqueue mit Standard-Priorität 0, sofort verfügbar. */
    UUID enqueue(String type, Object payload);

    /** Enqueue mit expliziter Priorität (höher = wichtiger, R14). */
    UUID enqueue(String type, Object payload, int priority);

    /** Vollständig konfigurierter Enqueue über {@link EnqueueRequest}. */
    UUID enqueue(EnqueueRequest request);

    /** Geplante Ausführung ab einem absoluten Zeitpunkt (R18). */
    UUID enqueueAt(String type, Object payload, Instant availableAt);

    /** Verzögerte Ausführung nach Ablauf einer Dauer (R18). */
    UUID enqueueAfter(String type, Object payload, Duration delay);
}
