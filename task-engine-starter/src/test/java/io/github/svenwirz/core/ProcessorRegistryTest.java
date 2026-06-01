package io.github.svenwirz.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.svenwirz.api.TaskProcessor;

/**
 * R2 — Dispatch über {@code type()}. Zusätzlich: Erkennung doppelt registrierter Typen
 * und Payload-Typ-Auflösung für generische Processors (R25).
 */
class ProcessorRegistryTest {

    record Email(String to, String subject) {
    }

    static class EmailProcessor implements TaskProcessor<Email> {
        @Override
        public String type() {
            return "email";
        }

        @Override
        public void process(Email payload) {
        }
    }

    static class RawProcessor implements TaskProcessor<String> {
        @Override
        public String type() {
            return "raw";
        }

        @Override
        public void process(String payload) {
        }
    }

    @Test
    void resolvesProcessorByType() { // R2
        ProcessorRegistry registry = new ProcessorRegistry(List.of(new EmailProcessor(), new RawProcessor()));

        assertThat(registry.find("email")).containsInstanceOf(EmailProcessor.class);
        assertThat(registry.find("raw")).containsInstanceOf(RawProcessor.class);
        assertThat(registry.isKnown("email")).isTrue();
    }

    @Test
    void unknownTypeIsNotResolved() { // R8 — Grundlage für sicheres Scheitern
        ProcessorRegistry registry = new ProcessorRegistry(List.of(new EmailProcessor()));

        assertThat(registry.find("does-not-exist")).isEmpty();
        assertThat(registry.isKnown("does-not-exist")).isFalse();
    }

    @Test
    void duplicateTypesAreRejected() {
        TaskProcessor<String> a = new RawProcessor();
        TaskProcessor<String> b = new RawProcessor();

        assertThatThrownBy(() -> new ProcessorRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("raw");
    }

    @Test
    void resolvesTypedPayloadClass() { // R25
        assertThat(new EmailProcessor().payloadType()).isEqualTo(Email.class);
    }

    @Test
    void resolvesRawStringPayloadClass() { // R25 — Rückwärtskompatibilität
        assertThat(new RawProcessor().payloadType()).isEqualTo(String.class);
    }
}
