package com.roubao.common.thread.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 虚拟线程工具类（JDK 21+）
 *
 * @author roubao
 * @since 1.0
 */
@Slf4j
public final class VirtualThreadUtil {

    /** 私有构造器 */
    private VirtualThreadUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** 全局异步池，JVM 关闭时自动关闭 */
    private static final ExecutorService ASYNC_POOL = Executors.newVirtualThreadPerTaskExecutor();

    static {
        // JVM 关闭时优雅关闭
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ASYNC_POOL.shutdown();
            try {
                if (!ASYNC_POOL.awaitTermination(10, TimeUnit.SECONDS)) {
                    ASYNC_POOL.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ASYNC_POOL.shutdownNow();
            }
        }));
    }

    /**
     * 同步执行并返回结果
     *
     * @param supplier 任务
     * @param <T>      返回类型
     * @return 结果
     */
    public static <T> T submitAndGet(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(supplier::get).get();
        } catch (Exception e) {
            throw propagate(e);
        }
    }

    /**
     * 异步提交 Runnable。
     *
     * @param runnable 任务
     * @return Future
     */
    public static Future<?> submit(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable cannot be null");
        return ASYNC_POOL.submit(runnable);
    }

    /**
     * 异步提交 Callable。
     *
     * @param callable 任务
     * @param <T>      返回类型
     * @return Future
     */
    public static <T> Future<T> submit(Callable<T> callable) {
        Objects.requireNonNull(callable, "callable cannot be null");
        return ASYNC_POOL.submit(callable);
    }

    /**
     * 异步提交 Supplier。
     *
     * @param supplier 任务
     * @param <T>      返回类型
     * @return Future
     */
    public static <T> Future<T> submit(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        return ASYNC_POOL.submit(supplier::get);
    }

    /**
     * 并行执行多个任务并返回结果。
     *
     * @param tasks 任务数组
     * @param <T>   结果类型
     * @return 结果列表
     */
    @SafeVarargs
    public static <T> List<T> parallelAndGet(Supplier<T>... tasks) {
        Objects.requireNonNull(tasks, "tasks cannot be null");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = Arrays.stream(tasks)
                    .map(task -> executor.submit(task::get))
                    .toList();

            return futures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw propagate(e);
                        }
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * 智能传播异常：恢复中断 + 解包 + 避免双重包装。
     *
     * @param t 异常
     * @return RuntimeException
     */
    static RuntimeException propagate(Throwable t) {
        Throwable cause = t;

        // 恢复中断
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }

        // 解包
        if (t instanceof ExecutionException || t instanceof CompletionException) {
            cause = t.getCause();
            if (cause == null) cause = t;
        }

        log.warn("[VirtualThreadUtil] propagate exception: {}", cause.getMessage(), cause);

        // 直接抛原始运行时异常
        if (cause instanceof RuntimeException re) {
            throw re;
        }

        // 包装其他
        throw new RuntimeException("Virtual thread task failed", cause);
    }
}