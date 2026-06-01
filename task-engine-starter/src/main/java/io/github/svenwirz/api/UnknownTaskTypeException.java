package io.github.svenwirz.api;

/**
 * Wird geworfen, wenn für einen Task-Typ kein {@link TaskProcessor} registriert ist
 * (R8). Die betroffene Task wird als Failure behandelt.
 */
public class UnknownTaskTypeException extends RuntimeException {

    public UnknownTaskTypeException(String type) {
        super("Kein TaskProcessor für Typ registriert: '" + type + "'");
    }
}
