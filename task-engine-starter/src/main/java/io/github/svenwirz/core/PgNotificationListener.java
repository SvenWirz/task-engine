package io.github.svenwirz.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hält eine dedizierte Connection auf {@code LISTEN task_new} (R11/R6b) und weckt den
 * Dispatcher, sobald der DB-Trigger {@code pg_notify} feuert. Bei Verbindungsabbruch
 * wird mit Backoff neu verbunden; der Fallback-Poll des Dispatchers überbrückt Lücken.
 *
 * <p>Wird nur instanziiert, wenn die DB PostgreSQL ist.
 */
public class PgNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PgNotificationListener.class);
    private static final String CHANNEL = "task_new";

    private final DataSource dataSource;
    private final Wakeup wakeup;

    private volatile boolean running = false;
    private Thread thread;

    public PgNotificationListener(DataSource dataSource, Wakeup wakeup) {
        this.dataSource = dataSource;
        this.wakeup = wakeup;
    }

    public void start() {
        running = true;
        thread = new Thread(this::listenLoop, "task-engine-notify");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void listenLoop() {
        long backoffMs = 500;
        while (running) {
            try (Connection conn = dataSource.getConnection()) {
                PGConnection pg = conn.unwrap(PGConnection.class);
                try (Statement st = conn.createStatement()) {
                    st.execute("LISTEN " + CHANNEL);
                }
                backoffMs = 500;
                log.debug("LISTEN {} aktiv", CHANNEL);
                // Direkt einmal wecken, falls vor dem LISTEN bereits Tasks anstanden.
                wakeup.signal();
                while (running && !conn.isClosed()) {
                    PGNotification[] notifications = pg.getNotifications(5000);
                    if (notifications != null && notifications.length > 0) {
                        wakeup.signal();
                    }
                }
            } catch (SQLException e) {
                if (!running) {
                    return;
                }
                log.warn("NOTIFY-Listener-Verbindung verloren, reconnect in {} ms: {}",
                        backoffMs, e.getMessage());
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
