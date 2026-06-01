package io.github.svenwirz.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.svenwirz.api.TaskProcessor;

/**
 * Registriert alle {@link TaskProcessor}-Beans nach ihrem {@code type()} und löst
 * sie beim Dispatch auf (R2). Doppelte Typen sind ein Konfigurationsfehler.
 */
public class ProcessorRegistry {

    private final Map<String, TaskProcessor<?>> byType = new HashMap<>();

    public ProcessorRegistry(List<TaskProcessor<?>> processors) {
        for (TaskProcessor<?> p : processors) {
            TaskProcessor<?> prev = byType.put(p.type(), p);
            if (prev != null) {
                throw new IllegalStateException(
                        "Mehrere TaskProcessor für Typ '" + p.type() + "': "
                                + prev.getClass().getName() + " und " + p.getClass().getName());
            }
        }
    }

    public Optional<TaskProcessor<?>> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    public boolean isKnown(String type) {
        return byType.containsKey(type);
    }
}
