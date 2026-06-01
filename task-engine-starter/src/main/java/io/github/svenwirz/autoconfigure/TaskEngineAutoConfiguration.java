package io.github.svenwirz.autoconfigure;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.svenwirz.api.TaskProcessor;
import io.github.svenwirz.api.TaskService;
import io.github.svenwirz.config.TaskEngineProperties;
import io.github.svenwirz.core.DefaultTaskService;
import io.github.svenwirz.core.EngineMetrics;
import io.github.svenwirz.core.ProcessorRegistry;
import io.github.svenwirz.core.Reaper;
import io.github.svenwirz.core.RetentionJob;
import io.github.svenwirz.core.RetryPolicyResolver;
import io.github.svenwirz.core.TaskDispatcher;
import io.github.svenwirz.core.TaskEngineLifecycle;
import io.github.svenwirz.core.TaskExecutionRunner;
import io.github.svenwirz.core.TraceContextProvider;
import io.github.svenwirz.core.WorkerManager;
import io.github.svenwirz.persistence.SqlDialect;
import io.github.svenwirz.persistence.TaskRepository;

/**
 * Zentrale Auto-Konfiguration der Task-Engine.
 *
 * <p>Die <b>Enqueue-Seite</b> ({@link TaskService}, Repository, Registry) wird immer
 * bereitgestellt — auch auf reinen Enqueuer-Knoten. Die <b>Worker-Seite</b> (Dispatcher,
 * Pool, Reaper, Retention) ist über {@code taskengine.enabled} schaltbar (Default an).
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, JdbcTemplate.class})
@EnableConfigurationProperties(TaskEngineProperties.class)
public class TaskEngineAutoConfiguration {

    // ------------------------------------------------------------- Gemeinsame Beans

    @Bean
    @ConditionalOnMissingBean
    public SqlDialect taskEngineSqlDialect(DataSource dataSource) {
        return new SqlDialect(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskRepository taskRepository(DataSource dataSource, SqlDialect dialect) {
        return new TaskRepository(new JdbcTemplate(dataSource), dialect);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessorRegistry taskProcessorRegistry(ObjectProvider<TaskProcessor<?>> processors) {
        return new ProcessorRegistry(processors.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryPolicyResolver retryPolicyResolver(TaskEngineProperties props, ProcessorRegistry registry) {
        return new RetryPolicyResolver(props, registry);
    }

    @Bean
    @ConditionalOnMissingBean(TraceContextProvider.class)
    public TraceContextProvider taskEngineTraceContextProvider() {
        return new TraceContextProvider.NoOp();
    }

    @Bean
    @ConditionalOnMissingBean(EngineMetrics.class)
    public EngineMetrics taskEngineNoOpMetrics() {
        return EngineMetrics.NOOP;
    }

    /**
     * Verwendet den ObjectMapper der Anwendung, falls vorhanden, sonst einen mit
     * JSR-310-Modul. Bewusst <i>kein</i> eigener ObjectMapper-Bean, um nicht mit dem
     * von Spring Boot/Web bereitgestellten Mapper zu kollidieren.
     */
    static ObjectMapper resolveObjectMapper(ObjectProvider<ObjectMapper> provider) {
        return provider.getIfAvailable(() -> new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Bean
    @ConditionalOnMissingBean(TaskService.class)
    public TaskService taskService(TaskRepository repository,
                                   ObjectProvider<ObjectMapper> objectMapper,
                                   RetryPolicyResolver retryPolicyResolver,
                                   TraceContextProvider traceContext) {
        return new DefaultTaskService(repository, resolveObjectMapper(objectMapper),
                retryPolicyResolver, traceContext);
    }

    // ------------------------------------------------------------- Worker-Seite (schaltbar)

    @AutoConfiguration
    @ConditionalOnProperty(prefix = "taskengine", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class WorkerConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "taskEngineNodeId")
        public String taskEngineNodeId(TaskEngineProperties props) {
            if (props.getNodeId() != null && !props.getNodeId().isBlank()) {
                return props.getNodeId();
            }
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "node";
            }
            String pid = ManagementFactory.getRuntimeMXBean().getName();
            return host + "-" + pid;
        }

        @Bean(name = "taskEngineExecutor")
        @ConditionalOnMissingBean(name = "taskEngineExecutor")
        public ThreadPoolTaskExecutor taskEngineExecutor(TaskEngineProperties props) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(props.getConcurrency());
            executor.setMaxPoolSize(props.getConcurrency());
            // Backpressure regelt das In-Flight-Semaphore; kleine Queue als Übergabe-Puffer.
            executor.setQueueCapacity(props.getConcurrency());
            executor.setThreadNamePrefix("task-engine-worker-");
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds((int) props.getShutdownTimeout().toSeconds());
            executor.initialize();
            return executor;
        }

        @Bean(name = "taskEngineWatchdog", destroyMethod = "shutdownNow")
        @ConditionalOnMissingBean(name = "taskEngineWatchdog")
        public ScheduledExecutorService taskEngineWatchdog() {
            return Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "task-engine-watchdog");
                t.setDaemon(true);
                return t;
            });
        }

        @Bean
        @ConditionalOnMissingBean
        public TransactionTemplate taskEngineTransactionTemplate(PlatformTransactionManager txManager) {
            return new TransactionTemplate(txManager);
        }

        @Bean
        @ConditionalOnMissingBean
        public TaskExecutionRunner taskExecutionRunner(TaskRepository repository,
                                                       ProcessorRegistry registry,
                                                       RetryPolicyResolver retryPolicyResolver,
                                                       ObjectProvider<ObjectMapper> objectMapper,
                                                       TraceContextProvider traceContext,
                                                       EngineMetrics metrics,
                                                       TransactionTemplate taskEngineTransactionTemplate,
                                                       TaskEngineProperties props,
                                                       ScheduledExecutorService taskEngineWatchdog) {
            return new TaskExecutionRunner(repository, registry, retryPolicyResolver,
                    resolveObjectMapper(objectMapper), traceContext, metrics, taskEngineTransactionTemplate,
                    props, taskEngineWatchdog);
        }

        @Bean
        @ConditionalOnMissingBean
        public WorkerManager taskEngineWorkerManager(TaskRepository repository,
                                                     TaskExecutionRunner runner,
                                                     @org.springframework.beans.factory.annotation.Qualifier("taskEngineExecutor")
                                                     Executor executor,
                                                     TaskEngineProperties props,
                                                     EngineMetrics metrics,
                                                     String taskEngineNodeId) {
            return new WorkerManager(repository, runner, executor, props, metrics, taskEngineNodeId);
        }

        @Bean
        @ConditionalOnMissingBean
        public TaskDispatcher taskEngineDispatcher(WorkerManager workerManager, TaskEngineProperties props) {
            TaskDispatcher dispatcher = new TaskDispatcher(workerManager, props);
            workerManager.setWakeup(dispatcher);
            return dispatcher;
        }

        @Bean
        @ConditionalOnMissingBean
        public Reaper taskEngineReaper(TaskRepository repository,
                                       TaskEngineProperties props,
                                       TaskDispatcher dispatcher) {
            return new Reaper(repository, props, dispatcher);
        }

        @Bean
        @ConditionalOnMissingBean
        public RetentionJob taskEngineRetentionJob(TaskRepository repository,
                                                   DataSource dataSource,
                                                   SqlDialect dialect,
                                                   TaskEngineProperties props) {
            return new RetentionJob(repository, new JdbcTemplate(dataSource), dialect, props);
        }

        @Bean
        @ConditionalOnMissingBean
        public TaskEngineLifecycle taskEngineLifecycle(TaskDispatcher dispatcher,
                                                       WorkerManager workerManager,
                                                       Reaper reaper,
                                                       RetentionJob retentionJob,
                                                       TaskEngineProperties props,
                                                       DataSource dataSource,
                                                       SqlDialect dialect) {
            return new TaskEngineLifecycle(dispatcher, workerManager, reaper, retentionJob,
                    props, dataSource, dialect);
        }
    }
}
