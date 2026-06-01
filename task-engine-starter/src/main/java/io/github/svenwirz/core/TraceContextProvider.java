package io.github.svenwirz.core;

/**
 * Liefert die aktuelle Trace-/Span-ID beim Enqueue (R3) und erlaubt das Öffnen eines
 * Span-Scopes bei der Verarbeitung. Default-Implementierung ist ein No-Op; eine
 * Micrometer-Tracing-Variante wird nur bei vorhandenem Classpath aktiviert (R7).
 */
public interface TraceContextProvider {

    /** Aktuelle Trace-ID oder {@code null}, wenn kein Tracing aktiv ist. */
    String currentTraceId();

    /** Aktuelle Span-ID oder {@code null}. */
    String currentSpanId();

    /**
     * Öffnet bei der Verarbeitung einen Scope für die gestempelten IDs. Das
     * zurückgegebene {@link Scope} wird nach der Verarbeitung geschlossen.
     */
    Scope openScope(String traceId, String spanId);

    /** Schließbarer Scope ohne checked Exception. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** Gemeinsamer No-Op-Scope. */
    Scope NOOP_SCOPE = () -> { };

    /** No-Op-Implementierung als Fallback ohne Tracing-Classpath. */
    final class NoOp implements TraceContextProvider {
        @Override
        public String currentTraceId() {
            return null;
        }

        @Override
        public String currentSpanId() {
            return null;
        }

        @Override
        public Scope openScope(String traceId, String spanId) {
            return NOOP_SCOPE;
        }
    }
}
