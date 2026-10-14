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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apposed.appose.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;

/**
 * Stateless Appose plumbing shared by the Appose extensions.
 *
 * <h2>COPIED BETWEEN REPOS -- keep the copies identical</h2>
 *
 * <p>The same source lives in {@code qupath-extension-cell-analysis-tools},
 * {@code -dl-pixel-classifier}, {@code -cellAPpose}, {@code -ppm} and
 * {@code -fiber-analysis}, differing only in the package line. There is no
 * shared library on purpose: an extension is not a dependency another extension
 * can declare, and a library would be one more artifact to version and support.
 * Instead {@code tools/check_appose_copies.py} compares the copies by meaning,
 * {@code tools/check_appose_copies.py --sync --from <repo>} pushes a change to
 * every sibling, and the pre-push hook refuses a push that leaves them apart.
 * Record why each change was made in
 * {@code claude-reports/design/appose/SHARED_COPIES.md}.
 *
 * <p>Nothing here holds state and nothing here knows which extension it is in:
 * every per-extension value (dialog titles, environment names, preferences, log
 * prefixes) is passed in by the caller.
 */
public final class ApposeSupport {

    private static final Logger logger = LoggerFactory.getLogger(ApposeSupport.class);

    /** The error text Appose attaches to a task whose worker thread stopped without reporting. */
    public static final String THREAD_DEATH_SIGNATURE = "thread death";

    /**
     * Highest {@code pixi.lock} format the pixi that Appose 0.12 bundles (pixi
     * 0.58.0) can read. A lock written by a newer pixi carries version 7 and
     * fails every install with "lock file version 7 ... only up to 6 supported".
     */
    public static final int MAX_LOCK_VERSION = 6;

    private static final Pattern LOCK_VERSION = Pattern.compile("(?m)^version:\\s*(\\d+)\\s*$");

    /** A {@link Runnable} that may throw; used by {@link #runWithExtensionClassLoader}. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private ApposeSupport() {}

    // ==================== Classloader ====================

    /**
     * The classloader Appose must see as the thread context classloader.
     *
     * <p>Appose finds its {@code ShmFactory}, {@code Scheme} and
     * {@code BuilderFactory} implementations through {@code ServiceLoader},
     * which reads the thread context classloader; from a JavaFX or worker
     * thread that is the application loader, which cannot see the extension
     * jar, and the lookup fails with a {@code NoSuchElementException} far from
     * the call that caused it.
     */
    public static ClassLoader extensionClassLoader() {
        return ApposeSupport.class.getClassLoader();
    }

    /** Runs {@code callable} with the extension classloader as the thread context classloader. */
    public static <T> T withExtensionClassLoader(Callable<T> callable) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(extensionClassLoader());
        try {
            return callable.call();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /** Runs {@code runnable} with the extension classloader as the thread context classloader. */
    public static void runWithExtensionClassLoader(ThrowingRunnable runnable) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(extensionClassLoader());
        try {
            runnable.run();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    // ==================== Resources ====================

    /**
     * Reads a classpath resource as UTF-8 text with {@code \n} line endings.
     *
     * @param resourcePath the path inside the jar, e.g. {@code qupath/ext/x/scripts/init.py}
     * @throws IOException if the resource does not exist
     */
    public static String loadResource(String resourcePath) throws IOException {
        try (InputStream is = extensionClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    // ==================== Manifest and lock ====================

    /**
     * Stages the bundled {@code pixi.toml} and {@code pixi.lock} into the
     * environment directory before a build.
     *
     * <p>The environment installs with {@code --frozen} from the staged lock,
     * so the lock on disk must be the bundled one. Four cases:
     * <ul>
     *   <li>No manifest on disk yet (first build): only the lock is staged;
     *       Appose writes the manifest itself.</li>
     *   <li>Manifest and lock match the bundled copies: nothing to do.</li>
     *   <li>Manifest matches but the lock is missing: the lock is re-staged and
     *       the environment is kept. A lock goes missing when a user deleted it
     *       or when this method just removed one written by a newer pixi (see
     *       {@link #MAX_LOCK_VERSION}); the frozen install re-syncs the
     *       environment to the bundled lock without a multi-gigabyte rebuild.</li>
     *   <li>Anything else changed: both files are rewritten and {@code .pixi}
     *       is removed so the next build reinstalls from scratch.</li>
     * </ul>
     *
     * <p>Never throws. A staging problem is logged and the build is attempted
     * anyway, because the failure that follows carries a better message.
     *
     * @return true when a rebuild was forced
     */
    public static boolean syncManifest(Path envDir, String expectedToml, String expectedLock, Logger log) {
        Logger out = log != null ? log : logger;
        try {
            Path tomlFile = envDir.resolve("pixi.toml");
            Path lockFile = envDir.resolve("pixi.lock");

            if (!Files.exists(tomlFile)) {
                Files.createDirectories(envDir);
                Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
                return false;
            }

            clearIncompatibleLock(lockFile, out);

            boolean tomlSame = normalize(Files.readString(tomlFile, StandardCharsets.UTF_8))
                    .equals(normalize(expectedToml));
            boolean lockPresent = Files.exists(lockFile);
            boolean lockSame = lockPresent
                    && normalize(Files.readString(lockFile, StandardCharsets.UTF_8))
                            .equals(normalize(expectedLock));

            if (tomlSame && lockSame) {
                return false;
            }
            if (tomlSame && !lockPresent) {
                Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
                out.info("Re-staged the bundled pixi.lock at {}; the environment is kept", envDir);
                return false;
            }

            out.info(
                    "The pixi manifest or lock at {} differs from the bundled copies; "
                            + "staging them and forcing a rebuild",
                    envDir);
            Files.writeString(tomlFile, expectedToml, StandardCharsets.UTF_8);
            Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
            removePixiDir(envDir.resolve(".pixi"), out);
            return true;
        } catch (IOException e) {
            out.warn("Could not stage the pixi manifest and lock at {} (building anyway): {}", envDir, e.getMessage());
            return false;
        }
    }

    /**
     * The {@code version:} field of a pixi.lock, or -1 when there is none.
     */
    public static int lockFormatVersion(String lockText) {
        if (lockText == null) {
            return -1;
        }
        Matcher m = LOCK_VERSION.matcher(lockText);
        if (!m.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Deletes an on-disk lock whose format is newer than the bundled pixi reads.
     *
     * <p>A developer who runs a current pixi against the environment gets the
     * lock silently rewritten to format 7, and from then on every install in
     * QuPath fails. Removing it lets {@link #syncManifest} re-stage the bundled
     * lock, which is the only one the bundled pixi can use.
     *
     * @return true if a lock was removed
     */
    static boolean clearIncompatibleLock(Path lockFile, Logger log) {
        Logger out = log != null ? log : logger;
        try {
            if (!Files.isRegularFile(lockFile)) {
                return false;
            }
            int version = lockFormatVersion(Files.readString(lockFile, StandardCharsets.UTF_8));
            if (version <= MAX_LOCK_VERSION) {
                return false;
            }
            Files.delete(lockFile);
            out.warn(
                    "Removed {}: it was written in lock format {} but the pixi bundled with Appose "
                            + "reads up to format {}. The bundled lock will be staged in its place.",
                    lockFile,
                    version,
                    MAX_LOCK_VERSION);
            return true;
        } catch (IOException e) {
            out.warn("Could not inspect {}: {}", lockFile, e.getMessage());
            return false;
        }
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").strip();
    }

    /**
     * Removes a {@code .pixi} directory, falling back to a rename when a locked
     * file blocks the delete (Windows). A rename is enough: the next build
     * creates a fresh {@code .pixi} beside the renamed one.
     */
    private static void removePixiDir(Path pixiDir, Logger log) {
        if (!Files.isDirectory(pixiDir)) {
            return;
        }
        try {
            deleteDirectoryRecursively(pixiDir);
        } catch (IOException e) {
            Path renamed = pixiDir.resolveSibling(".pixi_old_" + System.currentTimeMillis());
            try {
                Files.move(pixiDir, renamed);
                log.info("Could not delete {} (a file is locked); renamed it to {}", pixiDir, renamed.getFileName());
            } catch (IOException e2) {
                log.warn(
                        "Could not delete or rename {}: {}. Rebuild the environment from the "
                                + "extension's setup dialog once nothing else is using it.",
                        pixiDir,
                        e2.getMessage());
            }
        }
    }

    // ==================== pixi ====================

    /**
     * The pixi binary Appose downloaded, or the one on PATH, or null.
     *
     * <p>Appose keeps its pixi under the default Appose home even when the
     * environments are built elsewhere, so this does not take a base directory.
     */
    public static Path findPixiBinary() {
        Path apposeDir = Path.of(System.getProperty("user.home"), ".local", "share", "appose");
        String pixiName = GeneralTools.isWindows() ? "pixi.exe" : "pixi";
        Path pixi = apposeDir.resolve(".pixi").resolve("bin").resolve(pixiName);
        if (Files.isRegularFile(pixi)) {
            return pixi;
        }
        try {
            Process p = new ProcessBuilder(pixiName, "--version")
                    .redirectErrorStream(true)
                    .start();
            if (p.waitFor() == 0) {
                return Path.of(pixiName);
            }
        } catch (IOException e) {
            // not on PATH
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * Runs {@code pixi <args> --manifest-path <manifest>} in {@code workDir},
     * streaming every output line to {@code lineSink}.
     *
     * @param lineSink receives each line of combined stdout and stderr; null logs at INFO
     * @throws IOException if pixi cannot be started or exits non-zero (the message carries the output)
     */
    public static void runPixiCommand(Path pixi, Path workDir, Path manifest, Consumer<String> lineSink, String... args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(pixi.toString());
        for (String arg : args) {
            command.add(arg);
        }
        command.add("--manifest-path");
        command.add(manifest.toString());
        Consumer<String> sink = lineSink != null ? lineSink : line -> logger.info("[pixi] {}", line);

        logger.info("Running: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                sink.accept(line);
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("pixi command interrupted", e);
        }
        if (exitCode != 0) {
            String what = args.length > 0 ? args[0] : "command";
            throw new IOException("pixi " + what + " failed (exit code " + exitCode + "):\n" + output);
        }
    }

    // ==================== Failure classification ====================

    /**
     * The messages of a throwable and its whole cause chain, one per line.
     *
     * <p>pixi's {@code BuildException} says only "pixi build failed" at the
     * top; the text worth matching on lives several causes down.
     */
    public static String collectCauseMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        for (Throwable c = t; c != null && guard < 20; c = c.getCause(), guard++) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * True when a build failed because Windows held a lock on a file the conda
     * link step needed to replace. Do not wipe the environment in response: the
     * process holding the lock may still be writing.
     */
    public static boolean looksLikeWindowsFileLock(String messages) {
        if (messages == null) {
            return false;
        }
        String m = messages.toLowerCase(Locale.ROOT);
        return m.contains("failed to link")
                && (m.contains("os error 32") || m.contains("being used by another process"));
    }

    /** True when pixi refused the staged lock because of its format version. */
    public static boolean looksLikeLockFormatSkew(String messages) {
        if (messages == null) {
            return false;
        }
        String m = messages.toLowerCase(Locale.ROOT);
        return m.contains("lock file version")
                || m.contains("lockfile version")
                || (m.contains("lock") && (m.contains("failed to parse") || m.contains("could not parse")));
    }

    /** True when a task error carries Appose's stale-worker signature. */
    public static boolean isThreadDeath(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains(THREAD_DEATH_SIGNATURE);
    }

    /** {@link #isThreadDeath(String)} over a throwable and its cause chain. */
    public static boolean isThreadDeath(Throwable t) {
        return t != null && isThreadDeath(collectCauseMessages(t));
    }

    /**
     * Recovery steps for the Windows file-lock failure, naming the directory
     * that actually holds the environment.
     */
    public static String windowsFileLockAdvice(Path envDir) {
        return "The Python environment could not finish building because another"
                + " process is holding a file open inside the environment directory.\n\n"
                + "RECOVERY STEPS (Windows):\n"
                + "  1. Close QuPath completely (File -> Quit).\n"
                + "  2. Open Task Manager and end any leftover java.exe or python.exe"
                + " running under your user.\n"
                + "  3. In PowerShell:\n"
                + "       Remove-Item -Recurse -Force \"" + envDir.resolve(".pixi") + "\"\n"
                + "  4. If step 3 fails, reboot Windows; that releases every file handle.\n"
                + "  5. (Optional) Add an antivirus exclusion for " + envDir.getParent()
                + " to prevent repeat occurrences.\n"
                + "  6. Relaunch QuPath. The environment rebuilds from the bundled lock.";
    }

    // ==================== Worker process ====================

    /**
     * Stops a Python worker: closes its stdin for a clean exit, waits up to
     * {@code graceMs}, then kills it. Never throws; safe on a worker that was
     * never started.
     */
    public static void stopWorker(Service worker, long graceMs, Logger log) {
        if (worker == null) {
            return;
        }
        Logger out = log != null ? log : logger;
        try {
            worker.close();
            // monotonic: a wall-clock step (NTP, WSL resync) must not stretch or cut the grace period
            long start = System.nanoTime();
            while (worker.isAlive() && elapsedMs(start) < graceMs) {
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            out.warn("Error while closing the Python worker: {}", e.getMessage());
        }
        try {
            if (worker.isAlive()) {
                out.warn("The Python worker did not exit within {} ms; killing it", graceMs);
                worker.kill();
            }
        } catch (Exception e) {
            out.warn("Could not kill the Python worker: {}", e.getMessage());
        }
    }

    /**
     * Registers a non-daemon JVM shutdown hook that stops whatever worker
     * {@code current} returns at exit, so no Python process outlives QuPath.
     *
     * @return the hook thread, to hand back to {@link #removeShutdownHook}
     */
    public static Thread registerShutdownHook(String threadName, Supplier<Service> current, Logger log) {
        Logger out = log != null ? log : logger;
        Thread hook = new Thread(
                () -> {
                    Service worker = current.get();
                    if (worker == null) {
                        return;
                    }
                    out.info("JVM shutdown: stopping the Python worker");
                    stopWorker(worker, 2000, out);
                },
                threadName);
        hook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    /** Removes a hook from {@link #registerShutdownHook}; a no-op once shutdown has begun. */
    public static void removeShutdownHook(Thread hook) {
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // JVM already shutting down
        }
    }

    /**
     * True for the JSON request and response envelopes Appose echoes through
     * the debug listener. They are the protocol, not output, and a console
     * that shows them buries the lines a user is looking for.
     */
    public static boolean isProtocolEnvelope(String debugLine) {
        if (debugLine == null) {
            return false;
        }
        String t = debugLine.strip();
        return t.startsWith("{") && (t.contains("\"requestType\"") || t.contains("\"responseType\""));
    }

    // ==================== Files and timing ====================

    /** Deletes a directory tree. A missing directory is not an error. */
    public static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Milliseconds since a {@link System#nanoTime()} reading. */
    public static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
