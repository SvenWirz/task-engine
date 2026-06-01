package io.github.svenwirz.example;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.svenwirz.model.Task;
import io.github.svenwirz.model.TaskStatus;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Schlanke, server-gerenderte UI zur Demonstration der Task-Engine. Nutzt die
 * öffentlichen Beans des Starters ({@link TaskRepository}) und den {@link DemoService}.
 */
@Controller
public class DemoController {

    private final DemoService demoService;
    private final TaskRepository repository;

    public DemoController(DemoService demoService, TaskRepository repository) {
        this.demoService = demoService;
        this.repository = repository;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<Task> tasks = repository.list(null, null, null, 100, 0);
        model.addAttribute("tasks", tasks);
        model.addAttribute("counts", new Counts(
                repository.countByStatus(TaskStatus.PENDING),
                repository.countByStatus(TaskStatus.RUNNING),
                repository.countByStatus(TaskStatus.SUCCEEDED),
                repository.countByStatus(TaskStatus.FAILED),
                repository.countByStatus(TaskStatus.DEAD),
                repository.countByStatus(TaskStatus.CANCELLED)));
        return "index";
    }

    @PostMapping("/enqueue")
    public String enqueue(@RequestParam String type,
                          @RequestParam(defaultValue = "0") int priority,
                          @RequestParam(defaultValue = "5") int count,
                          RedirectAttributes ra) {
        for (int i = 0; i < Math.max(1, count); i++) {
            demoService.enqueueWithinCommittedTransaction(type, payloadFor(type, i), priority);
        }
        ra.addFlashAttribute("message", count + " '" + type + "'-Task(s) eingereiht (committet).");
        return "redirect:/";
    }

    @PostMapping("/enqueue-and-rollback")
    public String enqueueAndRollback(@RequestParam String type, RedirectAttributes ra) {
        try {
            demoService.enqueueThenRollback(type, payloadFor(type, 0), 0);
        } catch (RuntimeException expected) {
            ra.addFlashAttribute("message",
                    "R0-Demo: Transaktion mit Exception abgebrochen — es wurde KEINE Task angelegt.");
        }
        return "redirect:/";
    }

    @PostMapping("/tasks/{id}/retry")
    public String retry(@PathVariable UUID id, RedirectAttributes ra) {
        int updated = repository.requeueForManualRetry(id, true, Instant.now());
        ra.addFlashAttribute("message", updated > 0 ? "Task erneut eingereiht." : "Task nicht retrybar.");
        return "redirect:/";
    }

    @PostMapping("/tasks/{id}/cancel")
    public String cancel(@PathVariable UUID id, RedirectAttributes ra) {
        repository.markCancelled(id, Instant.now());
        ra.addFlashAttribute("message", "Task abgebrochen.");
        return "redirect:/";
    }

    @PostMapping("/tasks/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes ra) {
        repository.delete(id);
        ra.addFlashAttribute("message", "Task gelöscht.");
        return "redirect:/";
    }

    @PostMapping("/retry-dead")
    public String retryDead(RedirectAttributes ra) {
        int n = repository.requeueAllDead(true, Instant.now());
        ra.addFlashAttribute("message", n + " DEAD-Task(s) erneut eingereiht.");
        return "redirect:/";
    }

    private Object payloadFor(String type, int i) {
        return switch (type) {
            case "email" -> new EmailPayload("user" + i + "@example.com", "Willkommen #" + i);
            case "report" -> "{\"report\":\"monatsabschluss\",\"nr\":" + i + "}";
            default -> "{\"job\":" + i + "}";
        };
    }

    /** View-Model für die Status-Zähler. */
    public record Counts(long pending, long running, long succeeded,
                         long failed, long dead, long cancelled) {
    }
}
