package io.github.svenwirz.core;

/**
 * Metrik-Hook (R5). Default ist ein No-Op; eine Micrometer-Implementierung wird nur
 * bei vorhandenem Classpath aktiviert (R7), sodass die Engine ohne Micrometer
 * lauffähig bleibt.
 */
public interface EngineMetrics {

    void claimed(int count);

    void started(String type);

    void succeeded(String type, long durationNanos);

    void failedWillRetry(String type);

    void dead(String type);

    void timedOut(String type);

    /** No-Op-Fallback. */
    EngineMetrics NOOP = new EngineMetrics() {
        @Override
        public void claimed(int count) {
        }

        @Override
        public void started(String type) {
        }

        @Override
        public void succeeded(String type, long durationNanos) {
        }

        @Override
        public void failedWillRetry(String type) {
        }

        @Override
        public void dead(String type) {
        }

        @Override
        public void timedOut(String type) {
        }
    };
}
