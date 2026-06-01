package io.github.svenwirz.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.github.svenwirz.config.TaskEngineProperties;
import io.github.svenwirz.persistence.SqlDialect;

/**
 * Startet und stoppt die Worker-Seite der Engine als Spring-{@link SmartLifecycle}.
 * Bindet Dispatcher, NOTIFY-Listener und die periodischen Wartungsjobs (Reaper R12,
 * Retention R26) an den Anwendungs-Lebenszyklus und sorgt für Graceful Shutdown.
 *
 * <p>Der NOTIFY-Listener wird nur auf PostgreSQL gestartet; auf anderen DBs trägt
 * allein der Fallback-Poll des Dispatchers.
 */
public class TaskEngineLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TaskEngineLifecycle.class);

    private final TaskDispatcher dispatcher;
    private final WorkerManager workerManager;
    private final Reaper reaper;
    private final RetentionJob retentionJob;
    private final TaskEngineProperties properties;
    private final DataSource dataSource;
    private final SqlDialect dialect;

    private PgNotificationListener notificationListener;
    private ScheduledExecutorService maintenance;
    private volatile boolean running = false;

    public TaskEngineLifecycle(TaskDispatcher dispatcher,
                               WorkerManager workerManager,
                               Reaper reaper,
                               RetentionJob retentionJob,
                               TaskEngineProperties properties,
                               DataSource dataSource,
                               SqlDialect dialect) {
        this.dispatcher = dispatcher;
        this.workerManager = workerManager;
        this.reaper = reaper;
        this.retentionJob = retentionJob;
        this.properties = properties;
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public void start() {
        log.info("Task-Engine startet (concurrency={}, batchSize={}, postgres={})",
                properties.getConcurrency(), properties.getBatchSize(), dialect.isPostgres());
        maintenance = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "task-engine-maintenance");
            t.setDaemon(true);
            return t;
        });

        dispatcher.start();
        if (dialect.isPostgres()) {
            notificationListener = new PgNotificationListener(dataSource, dispatcher);
            notificationListener.start();
        }

        long reaperMs = Math.max(1000, properties.getReaperInterval().toMillis());
        maintenance.scheduleWithFixedDelay(reaper::runOnce, reaperMs, reaperMs, TimeUnit.MILLISECONDS);

        long retentionMs = Math.max(1000, properties.getRetention().getInterval().toMillis());
        maintenance.scheduleWithFixedDelay(retentionJob::runOnce, retentionMs, retentionMs, TimeUnit.MILLISECONDS);

        running = true;
    }

    @Override
    public void stop() {
        log.info("Task-Engine fährt herunter …");
        running = false;
        if (notificationListener != null) {
            notificationListener.stop();
        }
        dispatcher.stop();
        workerManager.stop();
        if (maintenance != null) {
            maintenance.shutdownNow();
        }
        // Laufende Tasks werden vom ThreadPoolTaskExecutor gemäß shutdown-timeout zu Ende geführt.
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Spät starten / früh stoppen, damit DataSource & Co. bereitstehen. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
