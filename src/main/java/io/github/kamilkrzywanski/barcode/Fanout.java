package io.github.kamilkrzywanski.barcode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs the independent pieces of one read. With an executor, every piece is handed to it and the
 * calling thread then runs, in order, each piece the executor has not started yet. A saturated
 * executor, or one whose own thread is the caller, therefore cannot deadlock a read. Without an
 * executor everything runs on the calling thread, and {@link #inOrder} does not start a piece
 * once {@code stop} is true.
 */
final class Fanout {
    private static final Fanout SEQUENTIAL = new Fanout(null);

    private final Executor executor;

    private Fanout(Executor executor) {
        this.executor = executor;
    }

    static Fanout sequential() {
        return SEQUENTIAL;
    }

    static Fanout on(Executor executor) {
        return new Fanout(executor);
    }

    /** The pool behind {@code new BarcodeReader()}: daemon threads, one fewer than the processors. */
    static Fanout shared() {
        return SharedPool.FANOUT;
    }

    /** Runs every task and returns when all of them have finished. */
    void runAll(List<Runnable> tasks) {
        if (executor == null || tasks.size() < 2) {
            tasks.forEach(Runnable::run);
            return;
        }
        List<FutureTask<Void>> futures = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            futures.add(submit(new FutureTask<>(task, null)));
        }
        for (FutureTask<Void> future : futures) {
            future.run();
            await(future);
        }
    }

    /**
     * Hands each result to {@code accept} in list order. {@code stop} is checked before each one;
     * once it is true, no further result is accepted, and pieces that have not started do not run.
     */
    <T> void inOrder(List<Callable<T>> jobs, BooleanSupplier stop, Consumer<T> accept) {
        if (executor == null) {
            for (Callable<T> job : jobs) {
                if (stop.getAsBoolean()) {
                    return;
                }
                accept.accept(call(job));
            }
            return;
        }
        List<FutureTask<T>> futures = new ArrayList<>(jobs.size());
        try {
            for (Callable<T> job : jobs) {
                futures.add(submit(new FutureTask<>(job)));
            }
            for (FutureTask<T> future : futures) {
                if (stop.getAsBoolean()) {
                    return;
                }
                future.run();
                T result = await(future);
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                accept.accept(result);
            }
        } finally {
            for (FutureTask<T> future : futures) {
                future.cancel(false);
            }
        }
    }

    private <T> FutureTask<T> submit(FutureTask<T> task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            // The caller runs it when its turn comes.
        }
        return task;
    }

    private static <T> T call(Callable<T> job) {
        try {
            return job.call();
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The result of a finished or running task. An interrupt returns null and keeps the flag set. */
    private static <T> T await(FutureTask<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static final class SharedPool {
        static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                runnable -> {
                    Thread thread = new Thread(runnable, "barcode-pro");
                    thread.setDaemon(true);
                    return thread;
                });
        static final Fanout FANOUT = new Fanout(EXECUTOR);

        private SharedPool() {
        }
    }
}
