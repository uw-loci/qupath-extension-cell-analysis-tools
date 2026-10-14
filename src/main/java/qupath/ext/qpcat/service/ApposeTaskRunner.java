/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.qpcat.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apposed.appose.Service;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.apposed.appose.TaskException;
import org.apposed.appose.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Submits an Appose task and recovers from a stale worker the one way that is
 * correct.
 *
 * <h2>COPIED BETWEEN REPOS -- keep the copies identical</h2>
 *
 * <p>Same arrangement as {@link ApposeSupport}: the file is copied into each
 * Appose extension, {@code tools/check_appose_copies.py} keeps the copies
 * together, and {@code claude-reports/design/appose/SHARED_COPIES.md} records
 * why each change was made.
 *
 * <h2>Why a retry needs all three parts</h2>
 *
 * <p>Appose reports "thread death" for two different failures. A worker that
 * has sat idle can fail its next task <em>before any Python runs</em>; Appose
 * then drops the task from its routing table and relaunches it inside the same
 * worker, where it keeps running as a zombie nobody can see, cancel or collect.
 * A task can also die part-way through real work. Only the first is safe to
 * retry, and only if the zombie is killed first:
 * <ol>
 *   <li><b>Replace the worker before retrying.</b> Resubmitting to the same
 *       worker runs the work twice, with one copy invisible: doubled GPU memory,
 *       doubled runtime, and files written by both.</li>
 *   <li><b>Retry only when no Python ran.</b> The first UPDATE event is the
 *       proof that the script started; a failure after it is a real failure and
 *       a rerun would repeat whatever the script already did.</li>
 *   <li><b>Log thread death at WARN.</b> It is expected and recovered; at ERROR
 *       every user report starts with a false alarm.</li>
 * </ol>
 *
 * <p>Locking is the caller's: hold whatever serialises your tasks around the
 * call, and make sure that lock is not the worker object itself, because the
 * worker is replaced mid-call.
 */
public final class ApposeTaskRunner {

    private static final Logger logger = LoggerFactory.getLogger(ApposeTaskRunner.class);

    /** Where tasks run and how a stale worker is replaced. */
    public interface WorkerSource {

        /**
         * The worker to submit to. May start one, or restart a dead one, on
         * the way; must not return null.
         */
        Service worker() throws IOException;

        /**
         * Replaces {@code stale} with a fresh worker, re-running whatever
         * initialisation the fresh one needs. Should be a no-op when another
         * thread has already replaced it.
         */
        void replaceWorker(Service stale) throws IOException;
    }

    /** Per-call settings; the defaults retry once with no delay. */
    public static final class Options {
        int maxAttempts = 2;
        long retryDelayMs = 0;
        Consumer<TaskEvent> listener;
        Consumer<Task> onCreated;
        String queue;

        /** Total attempts, including the first; at least 1. */
        public Options maxAttempts(int attempts) {
            this.maxAttempts = Math.max(1, attempts);
            return this;
        }

        /** Delay before attempt n is {@code n - 1} times this. */
        public Options retryDelayMs(long millis) {
            this.retryDelayMs = Math.max(0, millis);
            return this;
        }

        /** Receives every event of every attempt, after the runner's own bookkeeping. */
        public Options listener(Consumer<TaskEvent> listener) {
            this.listener = listener;
            return this;
        }

        /** Called with each attempt's task before it starts, e.g. to keep a cancel handle. */
        public Options onCreated(Consumer<Task> onCreated) {
            this.onCreated = onCreated;
            return this;
        }

        /** Appose queue name, or null for a thread per task. */
        public Options queue(String queue) {
            this.queue = queue;
            return this;
        }
    }

    private ApposeTaskRunner() {}

    /**
     * Runs {@code script} on the source's worker and waits for it.
     *
     * @param label    extension name for log lines, e.g. {@code "PPM"}
     * @param source   where to get the worker and how to replace it
     * @param taskName short name for log lines, e.g. the script file stem
     * @param script   the Python to run
     * @param inputs   task inputs; may be null
     * @param options  retry and listener settings; null for the defaults
     * @return the finished task, whose status is COMPLETE or CANCELED
     * @throws IOException if the task failed, crashed, could not be submitted,
     *                     or the thread was interrupted
     */
    public static Task run(String label, WorkerSource source, String taskName, String script,
            Map<String, Object> inputs, Options options) throws IOException {
        Options opts = options != null ? options : new Options();
        String what = label + " task '" + taskName + "'";

        for (int attempt = 1; ; attempt++) {
            Service worker = source.worker();
            if (worker == null) {
                throw new IOException(what + " cannot start: no Python worker is available");
            }

            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
            try {
                Task task = opts.queue != null
                        ? worker.task(script, inputs, opts.queue)
                        : worker.task(script, inputs);
                final AtomicBoolean pythonStarted = new AtomicBoolean(false);
                final int attemptNo = attempt;
                task.listen(event -> {
                    if (event.responseType == ResponseType.UPDATE) {
                        pythonStarted.set(true);
                    } else if (event.responseType == ResponseType.CRASH) {
                        logger.error("{} CRASH (attempt {}): {}", what, attemptNo, task.error);
                    } else if (event.responseType == ResponseType.FAILURE) {
                        if (ApposeSupport.isThreadDeath(task.error) && !pythonStarted.get()) {
                            logger.warn("{} FAILURE (attempt {}): stale worker, no Python ran: {}",
                                    what, attemptNo, task.error);
                        } else {
                            logger.error("{} FAILURE (attempt {}): {}", what, attemptNo, task.error);
                        }
                    }
                    if (opts.listener != null) {
                        try {
                            opts.listener.accept(event);
                        } catch (RuntimeException e) {
                            // The listener runs on Appose's reader thread; an exception there
                            // would stop every later event for every task.
                            logger.warn("{} listener threw on {}: {}", what, event.responseType, e.toString());
                        }
                    }
                });
                if (opts.onCreated != null) {
                    opts.onCreated.accept(task);
                }

                long started = System.nanoTime();
                try {
                    task.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    try {
                        task.cancel();
                    } catch (RuntimeException ignored) {
                        // best effort
                    }
                    throw new IOException(what + " interrupted", e);
                } catch (TaskException te) {
                    String message = te.getMessage() == null ? "" : te.getMessage();
                    boolean threadDeath = ApposeSupport.isThreadDeath(message);
                    boolean retryable = threadDeath && !pythonStarted.get() && attempt < opts.maxAttempts;
                    if (!retryable) {
                        if (threadDeath && pythonStarted.get()) {
                            logger.error("{} died after its Python had started; not retried, because a rerun "
                                    + "would repeat whatever the script already did", what);
                        }
                        throw new IOException(what + " failed: " + message, te);
                    }
                    logger.warn("{}: stale Python worker (attempt {}/{}); replacing the worker and retrying",
                            what, attempt, opts.maxAttempts);
                    long start = System.nanoTime();
                    try {
                        source.replaceWorker(worker);
                    } catch (IOException re) {
                        throw new IOException(what + " failed: " + message
                                + " (replacing the worker also failed: " + re.getMessage() + ")", te);
                    }
                    logger.info("{}: replaced the Python worker in {} ms", what, ApposeSupport.elapsedMs(start));
                    long delay = opts.retryDelayMs * attempt;
                    if (delay > 0) {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException(what + " interrupted before retry", ie);
                        }
                    }
                    continue;
                }

                if (attempt > 1) {
                    logger.info("{} succeeded on attempt {}", what, attempt);
                }
                logger.debug("{} finished with status {} in {} ms", what, task.status, ApposeSupport.elapsedMs(started));
                return task;
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }
}
