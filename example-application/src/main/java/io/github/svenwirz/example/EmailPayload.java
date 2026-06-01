package io.github.svenwirz.example;

/** Typisierter Payload für den {@link EmailProcessor} (demonstriert R25). */
public record EmailPayload(String to, String subject) {
}
