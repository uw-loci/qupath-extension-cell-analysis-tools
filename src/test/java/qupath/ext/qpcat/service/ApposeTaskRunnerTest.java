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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apposed.appose.Service;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.apposed.appose.Service.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives {@link ApposeTaskRunner} against a real Appose {@link Service} whose
 * worker is {@code fake_appose_worker.py} on the system {@code python3}, so the
 * retry decisions are tested over the real protocol rather than a mock.
 *
 * <p>COPIED BETWEEN REPOS together with the runner; see
 * {@code tools/check_appose_copies.py}. Skipped when no {@code python3} is on
 * the PATH.
 */
class ApposeTaskRunnerTest {

    private static final Logger logger = LoggerFactory.getLogger(ApposeTaskRunnerTest.class);
    private static final String WORKER_RESOURCE = "qupath/ext/qpcat/fake_appose_worker.py";

    @TempDir
    Path tmp;

    private Path workerScript;
    private Workers workers;

    /**
     * A worker source over the fake worker that counts replacements. With
     * {@code firstIsStale} the first worker is started with {@code --stale},
     * so it fails its first task the way an idle Appose worker does; a
     * replacement is started without it.
     */
    private final class Workers implements ApposeTaskRunner.WorkerSource {
        Service current;
        int replacements;
        boolean firstIsStale;
        final List<Service> spawned = new ArrayList<>();

        @Override
        public Service worker() {
            if (current == null) {
                current = spawn(firstIsStale);
            }
            return current;
        }

        @Override
        public void replaceWorker(Service stale) {
            if (stale != current) {
                return;
            }
            ApposeSupport.stopWorker(current, 1000, logger);
            current = spawn(false);
            replacements++;
        }

        private Service spawn(boolean stale) {
            Service s = stale
                    ? new Service(tmp.toFile(), "python3", "-I", workerScript.toString(), "--stale")
                    : new Service(tmp.toFile(), "python3", "-I", workerScript.toString());
            spawned.add(s);
            return s;
        }

        void close() {
            for (Service s : spawned) {
                ApposeSupport.stopWorker(s, 1000, logger);
            }
        }
    }

    @BeforeAll
    static void requirePython() {
        boolean available = false;
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            available = p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            // not available
        }
        assumeTrue(available, "python3 is not on the PATH; skipping the fake-worker tests");
    }

    @BeforeEach
    void extractWorker() throws IOException {
        workerScript = tmp.resolve("fake_appose_worker.py");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(WORKER_RESOURCE)) {
            if (is == null) {
                throw new IOException("Missing test resource " + WORKER_RESOURCE);
            }
            Files.copy(is, workerScript, StandardCopyOption.REPLACE_EXISTING);
        }
        workers = new Workers();
    }

    @AfterEach
    void stopWorkers() {
        workers.close();
    }

    private Task run(String mode, ApposeTaskRunner.Options options) throws IOException {
        return ApposeTaskRunner.run("Test", workers, mode, "#fake: " + mode + "\n", Map.of("x", 7), options);
    }

    @Test
    void completesOnTheFirstWorkerWithoutRetry() throws IOException {
        Task task = run("complete", null);
        assertEquals(TaskStatus.COMPLETE, task.status);
        assertEquals(0, workers.replacements);
        assertEquals(1, ((Number) task.outputs.get("ok")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> echo = (Map<String, Object>) task.outputs.get("echo");
        assertEquals(7, ((Number) echo.get("x")).intValue());
    }

    @Test
    void threadDeathBeforePythonStartedIsRetriedOnAFreshWorker() throws IOException {
        workers.firstIsStale = true;
        Task task = run("die-first", null);

        assertEquals(TaskStatus.COMPLETE, task.status);
        assertEquals(1, workers.replacements);
        // The answer came from the replacement, not the stale worker.
        assertTrue(workers.spawned.size() == 2);
        assertTrue(workers.current == workers.spawned.get(1));
    }

    @Test
    void aStaleWorkerIsNotReusedWhenRetriesAreOff() {
        workers.firstIsStale = true;
        ApposeTaskRunner.Options options = new ApposeTaskRunner.Options().maxAttempts(1);
        IOException e = assertThrows(IOException.class, () -> run("die-first", options));
        assertTrue(e.getMessage().contains("thread death"), e.getMessage());
        assertEquals(0, workers.replacements);
        // The same stale worker now completes its second task: this is exactly
        // the zombie-prone situation the retry-with-replacement avoids.
        assertEquals(1, workers.spawned.size());
    }

    @Test
    void threadDeathAfterAnUpdateIsNotRetried() {
        IOException e = assertThrows(IOException.class, () -> run("update-then-die", null));
        assertTrue(e.getMessage().contains("thread death"), e.getMessage());
        assertEquals(0, workers.replacements);
    }

    @Test
    void anOrdinaryFailureIsNotRetried() {
        IOException e = assertThrows(IOException.class, () -> run("fail", null));
        assertTrue(e.getMessage().contains("boom"), e.getMessage());
        assertEquals(0, workers.replacements);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        ApposeTaskRunner.Options options = new ApposeTaskRunner.Options().maxAttempts(3);
        IOException e = assertThrows(IOException.class, () -> run("die-always", options));
        assertTrue(e.getMessage().contains("thread death"), e.getMessage());
        assertEquals(2, workers.replacements);
    }

    @Test
    void aSingleAttemptNeverReplacesTheWorker() {
        ApposeTaskRunner.Options options = new ApposeTaskRunner.Options().maxAttempts(1);
        assertThrows(IOException.class, () -> run("die-always", options));
        assertEquals(0, workers.replacements);
    }

    @Test
    void listenerAndOnCreatedSeeTheTask() throws IOException {
        List<ResponseType> seen = new ArrayList<>();
        List<Task> created = new ArrayList<>();
        ApposeTaskRunner.Options options = new ApposeTaskRunner.Options()
                .listener(event -> seen.add(event.responseType))
                .onCreated(created::add);
        Task task = run("complete", options);
        assertEquals(1, created.size());
        assertTrue(created.get(0) == task);
        assertTrue(seen.contains(ResponseType.UPDATE), seen.toString());
        assertTrue(seen.contains(ResponseType.COMPLETION), seen.toString());
    }

    @Test
    void aThrowingListenerDoesNotBreakTheTask() throws IOException {
        ApposeTaskRunner.Options options = new ApposeTaskRunner.Options()
                .listener(event -> { throw new IllegalStateException("listener bug"); });
        Task task = run("complete", options);
        assertEquals(TaskStatus.COMPLETE, task.status);
        assertFalse(task.outputs.isEmpty());
    }
}
