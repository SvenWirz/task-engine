package io.github.svenwirz;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.github.svenwirz.api.TaskService;

/**
 * R13 — REST-Verwaltung. Prüft Auflisten/Filtern, Einzelabruf, manuellen Retry
 * (inkl. Konflikt-Fall und Bulk), Cancel, Delete und manuelles Anlegen über die
 * HTTP-Endpunkte. Worker-Seite ist aus; getestet wird allein die API.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:te-api;DB_CLOSE_DELAY=-1",
        "taskengine.enabled=false",
        "taskengine.api.enabled=true"
})
@AutoConfigureMockMvc
class TaskApiIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TaskService taskService;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task");
    }

    private UUID enqueue(String type) {
        return taskService.enqueue(type, "{\"k\":1}");
    }

    private void setStatus(UUID id, String status) {
        jdbc.update("UPDATE task SET status=? WHERE id=?", status, id);
    }

    @Test
    void createReturns201AndPersists() throws Exception {
        mvc.perform(post("/taskengine/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"email\",\"payload\":\"{\\\"to\\\":\\\"a@b.de\\\"}\",\"priority\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("email")))
                .andExpect(jsonPath("$.priority", is(5)))
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    void getByIdReturnsTaskOr404() throws Exception {
        UUID id = enqueue("email");

        mvc.perform(get("/taskengine/tasks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())));

        mvc.perform(get("/taskengine/tasks/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listFiltersByStatus() throws Exception {
        enqueue("email");
        UUID dead = enqueue("report");
        setStatus(dead, "DEAD");

        mvc.perform(get("/taskengine/tasks").param("status", "DEAD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].status", is("DEAD")));
    }

    @Test
    void retryMovesDeadToPending() throws Exception {
        UUID id = enqueue("email");
        setStatus(id, "DEAD");

        mvc.perform(post("/taskengine/tasks/{id}/retry", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    void retryOnNonRetryableStateReturns409() throws Exception {
        UUID id = enqueue("email"); // PENDING — nicht retrybar

        mvc.perform(post("/taskengine/tasks/{id}/retry", id))
                .andExpect(status().isConflict());
    }

    @Test
    void bulkRetryRequeuesDead() throws Exception {
        UUID a = enqueue("email");
        UUID b = enqueue("email");
        setStatus(a, "DEAD");
        setStatus(b, "DEAD");

        mvc.perform(post("/taskengine/tasks/retry-dead"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued", is(2)));
    }

    @Test
    void cancelSetsStatusCancelled() throws Exception {
        UUID id = enqueue("email");

        mvc.perform(post("/taskengine/tasks/{id}/cancel", id))
                .andExpect(status().isNoContent());

        mvc.perform(get("/taskengine/tasks/{id}", id))
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    @Test
    void deleteRemovesTask() throws Exception {
        UUID id = enqueue("email");

        mvc.perform(delete("/taskengine/tasks/{id}", id))
                .andExpect(status().isNoContent());

        mvc.perform(get("/taskengine/tasks/{id}", id))
                .andExpect(status().isNotFound());
    }
}
