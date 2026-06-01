package io.github.svenwirz.core;

/**
 * Signalisiert dem Dispatcher, dass es etwas zu tun geben könnte (neue Task per NOTIFY,
 * Fallback-Poll, freigewordene Kapazität). Entkoppelt Worker und Dispatcher.
 */
public interface Wakeup {
    void signal();
}
