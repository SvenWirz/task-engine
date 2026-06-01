package io.github.svenwirz.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Zentrale Konfiguration unter dem Präfix {@code taskengine}.
 */
@ConfigurationProperties(prefix = "taskengine")
public class TaskEngineProperties {

    /**
     * Schaltet die Worker-Seite (Dispatch/Verarbeitung) ein. Enqueue funktioniert
     * unabhängig davon — ein Knoten kann reiner Enqueuer sein.
     */
    private boolean enabled = true;

    /** Größe des dedizierten Worker-Thread-Pools. */
    private int concurrency = 8;

    /** Anzahl Tasks, die pro Claim-Runde maximal geholt werden. */
    private int batchSize = 16;

    /** Fallback-Poll-Intervall als Sicherheitsnetz gegen verpasste NOTIFYs. */
    private Duration pollInterval = Duration.ofSeconds(5);

    /**
     * Alter, ab dem eine RUNNING-Task als verwaist gilt und vom Reaper
     * requeued wird (Crash-Recovery, R12).
     */
    private Duration stuckAfter = Duration.ofMinutes(5);

    /** Intervall des Reaper-Laufs. */
    private Duration reaperInterval = Duration.ofMinutes(1);

    /** Default-Timeout für die Ausführung einer Task (R22). 0/null = kein Timeout. */
    private Duration timeout = Duration.ofMinutes(5);

    /** Frist, bis zu der laufende Tasks beim Shutdown zu Ende geführt werden. */
    private Duration shutdownTimeout = Duration.ofSeconds(30);

    /** Eindeutige Knoten-ID für Claiming/Recovery; default abgeleitet aus Host+PID. */
    private String nodeId;

    /** Globale Retry-Policy; pro Typ über {@link #retry} überschreibbar. */
    @NestedConfigurationProperty
    private RetryProperties defaultRetry = new RetryProperties();

    /** Typ-spezifische Retry-Overrides, Key = Task-Typ. */
    private Map<String, RetryProperties> retry = new LinkedHashMap<>();

    /** Typ-spezifische Parallelitäts-Limits (R15), Key = Task-Typ. */
    private Map<String, ProcessorLimit> processorLimits = new LinkedHashMap<>();

    /** Archivierung/Retention für SUCCEEDED-Tasks (R26). */
    @NestedConfigurationProperty
    private Retention retention = new Retention();

    /** REST-API (R13). */
    @NestedConfigurationProperty
    private Api api = new Api();

    public static class Retention {
        /** Aufbewahrung erfolgreicher Tasks, bevor gelöscht/archiviert wird. */
        private Duration succeeded = Duration.ofDays(7);
        /** Strategie: DELETE oder ARCHIVE (Move nach task_archive). */
        private Strategy strategy = Strategy.DELETE;
        /** Job überhaupt aktiv? */
        private boolean enabled = true;
        /** Lauf-Intervall des Retention-Jobs. */
        private Duration interval = Duration.ofHours(1);

        public enum Strategy { DELETE, ARCHIVE }

        public Duration getSucceeded() {
            return succeeded;
        }

        public void setSucceeded(Duration succeeded) {
            this.succeeded = succeeded;
        }

        public Strategy getStrategy() {
            return strategy;
        }

        public void setStrategy(Strategy strategy) {
            this.strategy = strategy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }

    public static class Api {
        /** REST-API aktivieren (zusätzlich zum Spring-MVC-Classpath-Gate). */
        private boolean enabled = false;
        /** Basis-Pfad der Endpunkte. */
        private String basePath = "/taskengine";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getStuckAfter() {
        return stuckAfter;
    }

    public void setStuckAfter(Duration stuckAfter) {
        this.stuckAfter = stuckAfter;
    }

    public Duration getReaperInterval() {
        return reaperInterval;
    }

    public void setReaperInterval(Duration reaperInterval) {
        this.reaperInterval = reaperInterval;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public RetryProperties getDefaultRetry() {
        return defaultRetry;
    }

    public void setDefaultRetry(RetryProperties defaultRetry) {
        this.defaultRetry = defaultRetry;
    }

    public Map<String, RetryProperties> getRetry() {
        return retry;
    }

    public void setRetry(Map<String, RetryProperties> retry) {
        this.retry = retry;
    }

    public Map<String, ProcessorLimit> getProcessorLimits() {
        return processorLimits;
    }

    public void setProcessorLimits(Map<String, ProcessorLimit> processorLimits) {
        this.processorLimits = processorLimits;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api;
    }
}
