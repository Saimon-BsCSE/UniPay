package bd.edu.uiu.unipay.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded worker pool for asynchronous background jobs (WebSocket notification
 * dispatch + audit trail writes) — mandatory multithreading requirement.
 *
 * <p>Sizing and rejection policy follow the proposal risk matrix
 * ("Server Thread Exhaustion"): a <b>bounded</b> queue plus
 * {@link ThreadPoolExecutor.AbortPolicy} protects the main payment threads —
 * under saturation a background job is rejected loudly (and logged by
 * {@link #handleUncaughtException}) instead of silently piling up work and
 * exhausting server threads.</p>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    public static final String EXECUTOR = "unipayExecutor";

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${app.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${app.async.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${app.async.queue-capacity:100}")
    private int queueCapacity;

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor unipayExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("unipay-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        // Fail fast on saturation: never let background work starve payment threads.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Override
    public ThreadPoolTaskExecutor getAsyncExecutor() {
        return unipayExecutor();
    }

    /** Logs tasks the bounded pool had to reject (saturation) without failing the caller. */
    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Async task rejected/failed on {}.{}(): {}",
                        method.getDeclaringClass().getSimpleName(), method.getName(),
                        ex instanceof RejectedExecutionException ? "executor saturated — task dropped" : ex.getMessage());
    }
}
