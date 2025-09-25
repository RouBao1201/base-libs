package com.roubao.common.thread.utils;

import cn.hutool.core.collection.CollUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 企业级 CompletableFuture 线程池静态工具类，提供异步任务提交、组合、优先级任务、监控和优雅关闭功能。
 * 支持动态配置和高并发场景。
 *
 * @author SongYanBin
 * @since 2025/9/20
 */
@Slf4j
public class CompletableFutureUtil {
    // 默认配置参数
    private static final int DEFAULT_CORE_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_MAX_POOL_SIZE = DEFAULT_CORE_POOL_SIZE * 2;
    private static final long DEFAULT_KEEP_ALIVE_TIME = 60L;
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;
    private static final String DEFAULT_THREAD_NAME_PREFIX = "CompletableFutureUtil-";

    // 默认线程池实例
    private static final ThreadPoolExecutor DEFAULT_EXECUTOR;
    private static final ScheduledThreadPoolExecutor DEFAULT_SCHEDULED_EXECUTOR;
    private static final AtomicBoolean SHUTDOWN_INITIATED = new AtomicBoolean(false);
    private static final AtomicLong TASK_COUNTER = new AtomicLong(0);
    private static final AtomicLong FAILED_TASK_COUNTER = new AtomicLong(0);

    static {
        // 初始化默认线程池
        ThreadPoolConfig config = new ThreadPoolConfig();
        config.setCorePoolSize(DEFAULT_CORE_POOL_SIZE);
        config.setMaxPoolSize(DEFAULT_MAX_POOL_SIZE);
        config.setKeepAliveTime(DEFAULT_KEEP_ALIVE_TIME);
        config.setTimeUnit(TimeUnit.SECONDS);
        config.setQueueCapacity(DEFAULT_QUEUE_CAPACITY);
        config.setThreadNamePrefix(DEFAULT_THREAD_NAME_PREFIX);
        config.setRejectedHandler(new CallerRunsPolicy());
        DEFAULT_EXECUTOR = createThreadPoolExecutor(config);
        DEFAULT_EXECUTOR.allowCoreThreadTimeOut(true);

        // 初始化默认定时线程池
        DEFAULT_SCHEDULED_EXECUTOR = new ScheduledThreadPoolExecutor(
                Math.max(1, DEFAULT_CORE_POOL_SIZE / 2),
                createThreadFactory("Scheduled" + DEFAULT_THREAD_NAME_PREFIX)
        );

        // JVM 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(CompletableFutureUtil::shutdown, "CompletableFutureUtil-Shutdown"));
    }

    /**
     * 线程池配置类
     */
    @Getter
    @Setter
    @ToString
    public static class ThreadPoolConfig {
        private int corePoolSize = DEFAULT_CORE_POOL_SIZE;
        private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
        private long keepAliveTime = DEFAULT_KEEP_ALIVE_TIME;
        private TimeUnit timeUnit = TimeUnit.SECONDS;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private String threadNamePrefix = DEFAULT_THREAD_NAME_PREFIX;
        private RejectedExecutionHandler rejectedHandler = new CallerRunsPolicy();
        private boolean priorityQueue = false;
    }

    /**
     * 创建自定义线程工厂
     */
    private static ThreadFactory createThreadFactory(String prefix) {
        return new ThreadFactory() {
            private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
            private final AtomicLong threadNum = new AtomicLong(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = defaultFactory.newThread(r);
                thread.setName(prefix + threadNum.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /**
     * 自定义拒绝策略：记录日志并由调用者执行
     */
    private static class CallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("Task rejected, executing in caller thread. Pool status: {}", getPoolStatus(executor));
            r.run();
        }
    }

    /**
     * 创建线程池（基于配置）
     */
    public static ThreadPoolExecutor createThreadPoolExecutor(ThreadPoolConfig config) {
        if (config == null) {
            log.error("ThreadPoolConfig is null");
            throw new IllegalArgumentException("ThreadPoolConfig cannot be null");
        }
        BlockingQueue<Runnable> queue = config.isPriorityQueue() ? new PriorityBlockingQueue<>(config.getQueueCapacity()) : new LinkedBlockingQueue<>(config.getQueueCapacity());
        return new ThreadPoolExecutor(
                config.getCorePoolSize(),
                config.getMaxPoolSize(),
                config.getKeepAliveTime(),
                config.getTimeUnit(),
                queue,
                createThreadFactory(config.getThreadNamePrefix()),
                config.getRejectedHandler()
        );
    }

    /**
     * 创建线程池（基于原始参数）
     */
    public static ThreadPoolExecutor createThreadPoolExecutor(
            int corePoolSize, int maxPoolSize, long keepAliveTime, TimeUnit timeUnit,
            int queueCapacity, String threadNamePrefix, RejectedExecutionHandler rejectedHandler) {
        if (corePoolSize <= 0 || maxPoolSize < corePoolSize || queueCapacity <= 0) {
            log.error("Invalid thread pool config: core={}, max={}, queue={}", corePoolSize, maxPoolSize, queueCapacity);
            throw new IllegalArgumentException("Invalid thread pool configuration");
        }
        return new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, keepAliveTime, timeUnit,
                new LinkedBlockingQueue<>(queueCapacity),
                createThreadFactory(threadNamePrefix),
                rejectedHandler
        );
    }

    /**
     * 创建优先级线程池
     */
    public static ThreadPoolExecutor createPriorityThreadPoolExecutor(
            int corePoolSize, int maxPoolSize, long keepAliveTime, TimeUnit timeUnit,
            int queueCapacity, String threadNamePrefix, RejectedExecutionHandler rejectedHandler) {
        if (corePoolSize <= 0 || maxPoolSize < corePoolSize) {
            log.error("Invalid thread pool config: core={}, max={}", corePoolSize, maxPoolSize);
            throw new IllegalArgumentException("Invalid thread pool configuration");
        }
        return new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, keepAliveTime, timeUnit,
                new PriorityBlockingQueue<>(queueCapacity),
                createThreadFactory(threadNamePrefix),
                rejectedHandler
        );
    }

    // ==================== CompletableFuture 异步任务方法 ====================

    /**
     * 异步执行 Supplier 任务
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return supplyAsync(DEFAULT_EXECUTOR, supplier);
    }

    /**
     * 在指定线程池异步执行 Supplier 任务
     */
    public static <T> CompletableFuture<T> supplyAsync(Executor executor, Supplier<T> supplier) {
        if (supplier == null) {
            log.error("Supplier is null");
            throw new IllegalArgumentException("Supplier cannot be null");
        }
        try {
            log.debug("Supplying async task to thread pool: {}", executor);
            CompletableFuture<T> future = CompletableFuture.supplyAsync(supplier, executor);
            TASK_COUNTER.incrementAndGet();
            future.exceptionally(throwable -> {
                log.error("Async task failed: {}", throwable.getMessage(), throwable);
                FAILED_TASK_COUNTER.incrementAndGet();
                throw new CompletionException(throwable);
            });
            return future;
        } catch (RejectedExecutionException e) {
            log.error("Async task submission rejected: {}", e.getMessage(), e);
            FAILED_TASK_COUNTER.incrementAndGet();
            throw e;
        }
    }

    /**
     * 异步执行优先级 Supplier 任务
     */
    public static <T> CompletableFuture<T> supplyAsyncPriority(Executor executor, Supplier<T> supplier, int priority) {
        if (!(executor instanceof ThreadPoolExecutor) || !(((ThreadPoolExecutor) executor).getQueue() instanceof PriorityBlockingQueue)) {
            log.error("Executor does not support priority tasks");
            throw new IllegalArgumentException("Executor must use PriorityBlockingQueue");
        }
        if (supplier == null) {
            log.error("Supplier is null");
            throw new IllegalArgumentException("Supplier cannot be null");
        }
        try {
            log.debug("Supplying priority async task with priority {}", priority);
            CompletableFuture<T> future = new CompletableFuture<>();
            PriorityRunnable priorityRunnable = new PriorityRunnable(() -> {
                try {
                    T result = supplier.get();
                    future.complete(result);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, priority);
            ((ThreadPoolExecutor) executor).execute(priorityRunnable);
            TASK_COUNTER.incrementAndGet();
            future.exceptionally(throwable -> {
                log.error("Priority async task failed: {}", throwable.getMessage(), throwable);
                FAILED_TASK_COUNTER.incrementAndGet();
                throw new CompletionException(throwable);
            });
            return future;
        } catch (RejectedExecutionException e) {
            log.error("Priority async task submission rejected: {}", e.getMessage(), e);
            FAILED_TASK_COUNTER.incrementAndGet();
            throw e;
        }
    }

    /**
     * 异步执行 Runnable 任务
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return runAsync(DEFAULT_EXECUTOR, runnable);
    }

    /**
     * 在指定线程池异步执行 Runnable 任务
     */
    public static CompletableFuture<Void> runAsync(Executor executor, Runnable runnable) {
        if (runnable == null) {
            log.error("Runnable is null");
            throw new IllegalArgumentException("Runnable cannot be null");
        }
        try {
            log.debug("Running async task in thread pool: {}", executor);
            CompletableFuture<Void> future = CompletableFuture.runAsync(runnable, executor);
            TASK_COUNTER.incrementAndGet();
            future.exceptionally(throwable -> {
                log.error("Async task failed: {}", throwable.getMessage(), throwable);
                FAILED_TASK_COUNTER.incrementAndGet();
                throw new CompletionException(throwable);
            });
            return future;
        } catch (RejectedExecutionException e) {
            log.error("Async task submission rejected: {}", e.getMessage(), e);
            FAILED_TASK_COUNTER.incrementAndGet();
            throw e;
        }
    }

    /**
     * 异步执行优先级 Runnable 任务
     */
    public static CompletableFuture<Void> runAsyncPriority(Executor executor, Runnable runnable, int priority) {
        if (!(executor instanceof ThreadPoolExecutor) || !(((ThreadPoolExecutor) executor).getQueue() instanceof PriorityBlockingQueue)) {
            log.error("Executor does not support priority tasks");
            throw new IllegalArgumentException("Executor must use PriorityBlockingQueue");
        }
        if (runnable == null) {
            log.error("Runnable is null");
            throw new IllegalArgumentException("Runnable cannot be null");
        }
        try {
            log.debug("Running priority async task with priority {}", priority);
            CompletableFuture<Void> future = new CompletableFuture<>();
            PriorityRunnable priorityRunnable = new PriorityRunnable(() -> {
                try {
                    runnable.run();
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, priority);
            ((ThreadPoolExecutor) executor).execute(priorityRunnable);
            TASK_COUNTER.incrementAndGet();
            future.exceptionally(throwable -> {
                log.error("Priority async task failed: {}", throwable.getMessage(), throwable);
                FAILED_TASK_COUNTER.incrementAndGet();
                throw new CompletionException(throwable);
            });
            return future;
        } catch (RejectedExecutionException e) {
            log.error("Priority async task submission rejected: {}", e.getMessage(), e);
            FAILED_TASK_COUNTER.incrementAndGet();
            throw e;
        }
    }

    /**
     * 优先级 Runnable 包装类
     */
    private static class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {
        private final Runnable task;
        private final int priority;

        public PriorityRunnable(Runnable task, int priority) {
            this.task = task;
            this.priority = priority;
        }

        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(PriorityRunnable other) {
            return Integer.compare(other.priority, this.priority); // 更高优先级先执行
        }
    }

    /**
     * 批量异步任务（返回 CompletableFuture<Void>）
     */
    public static CompletableFuture<Void> allOf(List<CompletableFuture<?>> futures) {
        if (CollUtil.isEmpty(futures)) {
            log.warn("Future list is empty");
            return CompletableFuture.completedFuture(null);
        }
        log.info("Combining {} futures with allOf", futures.size());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * 批量异步任务（返回第一个完成的 CompletableFuture<Object>）
     */
    public static CompletableFuture<Object> anyOf(List<CompletableFuture<?>> futures) {
        if (CollUtil.isEmpty(futures)) {
            log.warn("Future list is empty");
            return CompletableFuture.completedFuture(null);
        }
        log.info("Combining {} futures with anyOf", futures.size());
        return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * 批量提交 Supplier 任务
     */
    public static <T> List<CompletableFuture<T>> supplyAllAsync(List<Supplier<T>> suppliers) {
        return supplyAllAsync(DEFAULT_EXECUTOR, suppliers);
    }

    /**
     * 在指定线程池批量提交 Supplier 任务
     */
    public static <T> List<CompletableFuture<T>> supplyAllAsync(Executor executor, List<Supplier<T>> suppliers) {
        if (CollUtil.isEmpty(suppliers)) {
            log.warn("Supplier list is empty");
            return new ArrayList<>();
        }
        List<CompletableFuture<T>> futures = new ArrayList<>(suppliers.size());
        log.info("Supplying all {} async tasks to thread pool: {}", suppliers.size(), executor);
        for (Supplier<T> supplier : suppliers) {
            futures.add(supplyAsync(executor, supplier));
        }
        return futures;
    }

    /**
     * 定时异步执行 Supplier 任务
     */
    public static <T> CompletableFuture<T> supplyAsyncScheduled(Supplier<T> supplier, long delay, TimeUnit unit) {
        if (supplier == null) {
            log.error("Supplier is null");
            throw new IllegalArgumentException("Supplier cannot be null");
        }
        log.info("Scheduling async task with delay {} {}", delay, unit);
        CompletableFuture<T> future = new CompletableFuture<>();
        DEFAULT_SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                T result = supplier.get();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, delay, unit);
        TASK_COUNTER.incrementAndGet();
        return future.exceptionally(throwable -> {
            log.error("Scheduled async task failed: {}", throwable.getMessage(), throwable);
            FAILED_TASK_COUNTER.incrementAndGet();
            throw new CompletionException(throwable);
        });
    }

    /**
     * 定时异步执行 Runnable 任务
     */
    public static CompletableFuture<Void> runAsyncScheduled(Runnable runnable, long delay, TimeUnit unit) {
        if (runnable == null) {
            log.error("Runnable is null");
            throw new IllegalArgumentException("Runnable cannot be null");
        }
        log.info("Scheduling async Runnable task with delay {} {}", delay, unit);
        CompletableFuture<Void> future = new CompletableFuture<>();
        DEFAULT_SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, delay, unit);
        TASK_COUNTER.incrementAndGet();
        return future.exceptionally(throwable -> {
            log.error("Scheduled async task failed: {}", throwable.getMessage(), throwable);
            FAILED_TASK_COUNTER.incrementAndGet();
            throw new CompletionException(throwable);
        });
    }

    // ==================== 线程池管理方法 ====================

    /**
     * 获取默认线程池状态
     */
    public static String getDefaultPoolStatus() {
        return getPoolStatus(DEFAULT_EXECUTOR);
    }

    /**
     * 获取定时线程池状态
     */
    public static String getScheduledPoolStatus() {
        return String.format(
                "ScheduledThreadPool Status: Active=%d, PoolSize=%d, Core=%d, TaskCount=%d, Completed=%d",
                DEFAULT_SCHEDULED_EXECUTOR.getActiveCount(),
                DEFAULT_SCHEDULED_EXECUTOR.getPoolSize(),
                DEFAULT_SCHEDULED_EXECUTOR.getCorePoolSize(),
                DEFAULT_SCHEDULED_EXECUTOR.getTaskCount(),
                DEFAULT_SCHEDULED_EXECUTOR.getCompletedTaskCount()
        );
    }

    /**
     * 获取线程池状态
     */
    public static String getPoolStatus(ThreadPoolExecutor executor) {
        return String.format(
                "ThreadPool Status: Active=%d, PoolSize=%d, Core=%d, Max=%d, Largest=%d, Queue=%d/%d, TaskCount=%d, Completed=%d",
                executor.getActiveCount(),
                executor.getPoolSize(),
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getLargestPoolSize(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity(),
                executor.getTaskCount(),
                executor.getCompletedTaskCount()
        );
    }

    /**
     * 获取总任务计数
     */
    public static long getTaskCount() {
        return TASK_COUNTER.get();
    }

    /**
     * 获取失败任务计数
     */
    public static long getFailedTaskCount() {
        return FAILED_TASK_COUNTER.get();
    }

    /**
     * 优雅关闭线程池
     */
    public static void shutdown() {
        if (SHUTDOWN_INITIATED.compareAndSet(false, true)) {
            log.info("Shutting down CompletableFutureUtil, executor status: {}, scheduled status: {}",
                    getDefaultPoolStatus(), getScheduledPoolStatus());
            DEFAULT_EXECUTOR.shutdown();
            DEFAULT_SCHEDULED_EXECUTOR.shutdown();
            try {
                if (!DEFAULT_EXECUTOR.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("Default executor did not terminate within 60 seconds, forcing shutdown");
                    DEFAULT_EXECUTOR.shutdownNow();
                }
                if (!DEFAULT_SCHEDULED_EXECUTOR.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("Scheduled executor did not terminate within 60 seconds, forcing shutdown");
                    DEFAULT_SCHEDULED_EXECUTOR.shutdownNow();
                }
                log.info("ThreadPool shutdown completed, total tasks: {}, failed tasks: {}",
                        getTaskCount(), getFailedTaskCount());
            } catch (InterruptedException e) {
                log.error("Shutdown interrupted: {}", e.getMessage(), e);
                DEFAULT_EXECUTOR.shutdownNow();
                DEFAULT_SCHEDULED_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}