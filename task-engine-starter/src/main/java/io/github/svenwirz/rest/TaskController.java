package io.github.svenwirz.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.svenwirz.api.EnqueueRequest;
import io.github.svenwirz.api.TaskService;
import io.github.svenwirz.model.Task;
import io.github.svenwirz.model.TaskStatus;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * REST-Verwaltung der Tasks (R13). Aktiviert über {@code taskengine.api.enabled} und
 * den Spring-MVC-Classpath. Basis-Pfad konfigurierbar via {@code taskengine.api.base-path}.
 *
 * <p><b>Sicherheit:</b> Diese Endpunkte sind ungeschützt. Authentifizierung und
 * Autorisierung sind Sache der einbettenden Anwendung — niemals ungeschützt exponieren.
 */
@RestController
@RequestMapping("${taskengine.api.base-path:/taskengine}/tasks")
public class TaskController {

    private final TaskRepository repository;
    private final TaskService taskService;

    public TaskController(TaskRepository repository, TaskService taskService) {
        this.repository = repository;
        this.taskService = taskService;
    }

    /** Auflisten mit Filtern (Status/Typ/Priorität) und Pagination. */
    @GetMapping
    public List<TaskView> list(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer priority,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return repository.list(status, type, priority, clamp(limit), Math.max(0, offset))
                .stream().map(TaskView::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskView> get(@PathVariable UUID id) {
        return repository.findById(id)
                .map(TaskView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Manueller Retry: DEAD/FAILED → PENDING, sofort verfügbar (R13). */
    @PostMapping("/{id}/retry")
    public ResponseEntity<TaskView> retry(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean resetAttempts) {
        int updated = repository.requeueForManualRetry(id, resetAttempts, Instant.now());
        if (updated == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return repository.findById(id).map(TaskView::from).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Bulk-Retry aller DEAD-Tasks (R13). */
    @PostMapping("/retry-dead")
    public Map<String, Integer> retryDead(
            @RequestParam(defaultValue = "false") boolean resetAttempts) {
        int n = repository.requeueAllDead(resetAttempts, Instant.now());
        return Map.of("requeued", n);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        if (repository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repository.markCancelled(id, Instant.now());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return repository.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Optionales manuelles Anlegen — deckt sich mit {@link TaskService#enqueue}. */
    @PostMapping
    public ResponseEntity<TaskView> create(@RequestBody CreateTaskRequest req) {
        EnqueueRequest.Builder b = EnqueueRequest.of(req.type(), req.payload());
        if (req.priority() != null) {
            b.priority(req.priority());
        }
        if (req.availableAt() != null) {
            b.availableAt(req.availableAt());
        }
        if (req.idempotencyKey() != null) {
            b.idempotencyKey(req.idempotencyKey());
        }
        UUID id = taskService.enqueue(b.build());
        Task created = repository.findById(id).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(TaskView.from(created));
    }

    private static int clamp(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, 500);
    }
}
