package io.github.svenwirz.core;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.svenwirz.api.EnqueueRequest;
import io.github.svenwirz.api.TaskService;
import io.github.svenwirz.model.Task;
import io.github.svenwirz.model.TaskStatus;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Standard-Implementierung der Enqueue-API.
 *
 * <h2>R0 — die zentrale Garantie</h2>
 * {@code enqueue*} ist mit {@link Propagation#REQUIRED} annotiert und schreibt die
 * Task-Zeile über das {@link TaskRepository} in <i>genau die</i> Transaktion, die der
 * Aufrufer geöffnet hat. Damit gilt:
 * <ul>
 *   <li>Wirft der umgebende Geschäftsprozess nach dem Enqueue eine Exception, rollt die
 *       gesamte Transaktion zurück — inklusive der Task-Zeile. Es bleibt keine Task übrig.</li>
 *   <li>Erst der Commit der Geschäftstransaktion macht die Task sichtbar und feuert den
 *       DB-Trigger {@code NOTIFY}, der den Dispatcher weckt.</li>
 * </ul>
 * Niemals {@code REQUIRES_NEW} — das würde die Task unabhängig vom Geschäfts-Outcome
 * committen und R0 verletzen.
 */
public class DefaultTaskService implements TaskService {

    private final TaskRepository repository;
    private final ObjectMapper objectMapper;
    private final RetryPolicyResolver retryPolicyResolver;
    private final TraceContextProvider traceContext;

    public DefaultTaskService(TaskRepository repository,
                              ObjectMapper objectMapper,
                              RetryPolicyResolver retryPolicyResolver,
                              TraceContextProvider traceContext) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.retryPolicyResolver = retryPolicyResolver;
        this.traceContext = traceContext;
    }

    @Override
    public UUID enqueue(String type, Object payload) {
        return enqueue(EnqueueRequest.of(type, payload).build());
    }

    @Override
    public UUID enqueue(String type, Object payload, int priority) {
        return enqueue(EnqueueRequest.of(type, payload).priority(priority).build());
    }

    @Override
    public UUID enqueueAt(String type, Object payload, Instant availableAt) {
        return enqueue(EnqueueRequest.of(type, payload).availableAt(availableAt).build());
    }

    @Override
    public UUID enqueueAfter(String type, Object payload, Duration delay) {
        return enqueue(EnqueueRequest.of(type, payload).availableAfter(delay).build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public UUID enqueue(EnqueueRequest request) {
        Instant now = Instant.now();

        // R16: bei gesetztem Schlüssel zuerst auf vorhandene Task prüfen (No-Op-Enqueue).
        if (request.idempotencyKey() != null) {
            var existing = repository.findIdByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setType(request.type());
        task.setPayload(serialize(request.payload()));
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(request.priority());
        task.setAttempts(0);
        task.setMaxAttempts(request.maxAttempts() != null
                ? request.maxAttempts()
                : retryPolicyResolver.maxAttempts(request.type()));
        task.setAvailableAt(request.availableAt() != null ? request.availableAt() : now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setIdempotencyKey(request.idempotencyKey());
        // R3: Trace-Kontext zum Zeitpunkt des Enqueue festhalten.
        task.setTraceId(traceContext.currentTraceId());
        task.setSpanId(traceContext.currentSpanId());

        // R16: Race zwischen Check und Insert über Unique-Constraint abfangen.
        if (request.idempotencyKey() != null) {
            if (repository.insertConflicts(task)) {
                return repository.findIdByIdempotencyKey(request.idempotencyKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotenz-Konflikt ohne auffindbare Bestands-Task"));
            }
            return task.getId();
        }

        repository.insert(task);
        return task.getId();
    }

    private String serialize(Object payload) {
        if (payload == null) {
            return null;
        }
        // R25: Roh-JSON-String wird unverändert übernommen.
        if (payload instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload nicht serialisierbar: " + e.getMessage(), e);
        }
    }
}
