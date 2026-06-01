package io.github.svenwirz.api;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;

import io.github.svenwirz.config.RetryProperties;

/**
 * Typ-spezifischer Verarbeiter (R2, R25). Anwendungen registrieren beliebig viele
 * {@code TaskProcessor}-Beans; die Engine dispatcht anhand von {@link #type()}.
 *
 * <p>Der generische Parameter {@code T} ist der deserialisierte Payload-Typ. Für
 * rohe JSON-Verarbeitung kann {@code TaskProcessor<String>} implementiert werden —
 * dann wird der unveränderte JSON-String übergeben.
 *
 * <p><b>Delivery-Garantie:</b> Die Engine garantiert <i>at-least-once</i>. Ein Crash
 * nach erfolgreicher Verarbeitung, aber vor dem Commit, führt zu erneuter Ausführung.
 * Processors müssen daher idempotent sein (siehe {@code idempotencyKey} beim Enqueue
 * oder fachliche Idempotenz).
 *
 * @param <T> Payload-Typ
 */
public interface TaskProcessor<T> {

    /** Eindeutiger Typ-Schlüssel, über den Tasks diesem Processor zugeordnet werden. */
    String type();

    /**
     * Verarbeitet den Payload. Wirft die Methode eine Exception, gilt der Versuch als
     * fehlgeschlagen und wird gemäß Retry-Policy erneut eingeplant bzw. auf DEAD gesetzt.
     */
    void process(T payload) throws Exception;

    /**
     * Payload-Typ für die Jackson-Deserialisierung. Default löst {@code T} aus der
     * generischen Schnittstelle auf; bei {@code TaskProcessor<String>} wird der Roh-JSON
     * unverändert durchgereicht.
     */
    @SuppressWarnings("unchecked")
    default Class<T> payloadType() {
        for (Type iface : getClass().getGenericInterfaces()) {
            if (iface instanceof ParameterizedType pt
                    && pt.getRawType() == TaskProcessor.class) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> c) {
                    return (Class<T>) c;
                }
            }
        }
        // Nicht auflösbar (z. B. tiefere Generics-Hierarchie) → Roh-String.
        return (Class<T>) String.class;
    }

    /** Optionaler Pro-Knoten-Parallelitäts-Default; {@code null} = keine Vorgabe. */
    default Integer perNodeLimit() {
        return null;
    }

    /** Optionaler cluster-weiter Parallelitäts-Default; {@code null} = keine Vorgabe. */
    default Integer clusterWideLimit() {
        return null;
    }

    /** Optionale typ-spezifische Retry-Policy; {@code null} = globaler Default. */
    default RetryProperties retryPolicy() {
        return null;
    }

    /** Optionaler typ-spezifischer Timeout; {@code null} = globaler Default. */
    default Duration timeout() {
        return null;
    }
}
