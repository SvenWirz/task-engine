package io.github.svenwirz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.svenwirz.api.TaskProcessor;
import io.github.svenwirz.api.TaskService;

/**
 * R15 — Parallelitäts-Begrenzung pro Processor. Verifiziert sowohl die Pro-Knoten-
 * Semaphore als auch das cluster-weite DB-Kontingent: bei Limit 1 darf zu keinem
 * Zeitpunkt mehr als eine Task desselben Typs gleichzeitig laufen, obwohl der Pool
 * mehrere Threads frei hätte.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:te-concurrency;DB_CLOSE_DELAY=-1",
        "taskengine.enabled=true",
        "taskengine.poll-interval=80ms",
        "taskengine.concurrency=4",
        "taskengine.reaper-interval=1h",
        "taskengine.processor-limits.node.per-node=1",
        "taskengine.processor-limits.cluster.cluster-wide=1"
})
class ConcurrencyLimitIntegrationTest {

    @Autowired
    TaskService taskService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Counters nodeCounters;
    @Autowired
    Counters clusterCounters;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task");
        nodeCounters.reset();
        clusterCounters.reset();
    }

    private long succeeded(String type) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task WHERE type=? AND status='SUCCEEDED'", Long.class, type);
        return n == null ? 0 : n;
    }

    @Test
    void perNodeLimitSerializesExecution() {
        for (int i = 0; i < 4; i++) {
            taskService.enqueue("node", "{}");
        }
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(succeeded("node")).isEqualTo(4));
        assertThat(nodeCounters.max.get()).isEqualTo(1);
    }

    @Test
    void clusterWideLimitSerializesExecution() {
        for (int i = 0; i < 4; i++) {
            taskService.enqueue("cluster", "{}");
        }
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(succeeded("cluster")).isEqualTo(4));
        assertThat(clusterCounters.max.get()).isEqualTo(1);
    }

    static class Counters {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger max = new AtomicInteger();

        void enter() {
            int cur = active.incrementAndGet();
            max.accumulateAndGet(cur, Math::max);
        }

        void leave() {
            active.decrementAndGet();
        }

        void reset() {
            active.set(0);
            max.set(0);
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        Counters nodeCounters() {
            return new Counters();
        }

        @Bean
        Counters clusterCounters() {
            return new Counters();
        }

        @Bean
        TaskProcessor<String> nodeProcessor(Counters nodeCounters) {
            return blocking("node", nodeCounters);
        }

        @Bean
        TaskProcessor<String> clusterProcessor(Counters clusterCounters) {
            return blocking("cluster", clusterCounters);
        }

        private TaskProcessor<String> blocking(String type, Counters counters) {
            return new TaskProcessor<>() {
                @Override
                public String type() {
                    return type;
                }

                @Override
                public void process(String payload) throws InterruptedException {
                    counters.enter();
                    try {
                        Thread.sleep(200);
                    } finally {
                        counters.leave();
                    }
                }
            };
        }
    }
}
