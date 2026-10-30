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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.apposed.appose.BuildException;
import org.apposed.appose.Builder;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which NVIDIA GPU an Appose Python worker runs on, chosen the same way in
 * every extension.
 *
 * <h2>COPIED BETWEEN REPOS -- keep the copies identical</h2>
 *
 * <p>Same arrangement as {@link ApposeSupport}; see
 * {@code claude-reports/design/appose/SHARED_COPIES.md}.
 *
 * <h2>What it does</h2>
 * <ul>
 *   <li>Lists the cards with {@code nvidia-smi} once per QuPath session:
 *       index, UUID, name, compute capability, total and free memory. None
 *       on macOS, and none when no NVIDIA driver is installed.</li>
 *   <li>Chooses one: the card named by the shared preference
 *       {@value #PREF_KEY} (an index or a {@code GPU-} UUID), or, on
 *       {@code auto}, the card with the most FREE memory (in whole GiB, so a
 *       few MiB of noise between idle cards is a tie), then the most total
 *       memory, then the highest compute capability, then the lowest index.
 *       Free rather than total memory because the two common multi-GPU
 *       machines want it: on a desktop with a display card beside a compute
 *       card it picks the compute card, and on a shared server with identical
 *       cards it picks the least-loaded one instead of always card 0. Free
 *       memory is read once, when this extension first starts a worker, so
 *       its workers stay on one card for the session (another extension,
 *       reading later, may pick a different card, which spreads the load).</li>
 *   <li>Pins the worker to it: {@link #forWorker(Environment)} wraps an Appose
 *       environment so the worker starts with
 *       {@code CUDA_DEVICE_ORDER=PCI_BUS_ID} and {@code CUDA_VISIBLE_DEVICES}
 *       set to the card's UUID. Inside Python the card is then
 *       {@code cuda:0}, whatever its position, so no script needs changing.
 *       A UUID is used rather than an index because CUDA's default device
 *       order (fastest first) differs from {@code nvidia-smi}'s (PCI bus).</li>
 *   <li>Isolates every worker, CPU or GPU, from Python and native libraries
 *       QuPath's own environment points at ({@code ~/.local} site-packages,
 *       {@code PYTHONPATH}, {@code LD_LIBRARY_PATH}); see
 *       {@link #isolationVariables}.</li>
 *   <li>Respects a scheduler: when QuPath was started with
 *       {@code CUDA_VISIBLE_DEVICES} already set (SLURM and most cluster
 *       schedulers do this) and the preference is {@code auto}, nothing is
 *       pinned and the allocation is inherited.</li>
 *   <li>Checks the result: {@link #checkWorker} asks the worker's PyTorch
 *       which card it sees and whether it was built for that card's compute
 *       capability, and warns in plain words when it was not.</li>
 * </ul>
 *
 * <p>The preference is one key in one Java preferences node shared by every
 * Appose extension ({@link #NODE}), so a user picks a card once and every
 * extension's preference pane shows the same value. It can also be set for a
 * single run with the Java system property {@value #PREF_KEY}, e.g.
 * {@code -Dappose.gpuDevice=1}.
 */
public final class ApposeGpu {

    private static final Logger logger = LoggerFactory.getLogger(ApposeGpu.class);

    /** Shared preference key and system property; value {@code auto}, an index, or a {@code GPU-} UUID. */
    public static final String PREF_KEY = "appose.gpuDevice";

    /** Preferences node shared by every Appose extension; not QuPath's own, so no extension owns it. */
    public static final String NODE = "/io/github/apposed/qupath";

    /** The automatic choice. */
    public static final String AUTO = "auto";

    /** One NVIDIA card as {@code nvidia-smi} reports it. */
    public record Gpu(int index, String uuid, String name, String computeCapability, int memoryMiB, int freeMiB) {

        /** Compute capability as an integer, 86 for 8.6; -1 if unknown. */
        public int capabilityValue() {
            return ApposeGpu.capabilityValue(computeCapability);
        }

        /** E.g. {@code 1: NVIDIA GeForce RTX 3090 (compute 8.6, 24576 MiB)}. */
        public String describe() {
            return index + ": " + name + " (compute "
                    + (computeCapability == null || computeCapability.isBlank() ? "unknown" : computeCapability)
                    + ", " + memoryMiB + " MiB" + (freeMiB >= 0 ? ", " + freeMiB + " MiB free" : "") + ")";
        }

        /** Free memory in whole GiB for ranking; total memory when free is unknown. */
        int freeGiB() {
            return (freeMiB >= 0 ? freeMiB : memoryMiB) / 1024;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Gpu> cached;
    private static StringProperty preferenceProperty;
    private static boolean decisionLogged;

    private ApposeGpu() {}

    // ==================== Listing ====================

    /** The NVIDIA cards on this machine, probed once per session; empty when there are none. */
    public static List<Gpu> list() {
        synchronized (LOCK) {
            if (cached == null) {
                cached = Collections.unmodifiableList(probe());
            }
            return cached;
        }
    }

    private static List<Gpu> probe() {
        String smi = locateNvidiaSmi();
        if (smi == null) {
            return List.of();
        }
        String out = run(List.of(
                smi,
                "--query-gpu=index,uuid,name,compute_cap,memory.total,memory.free",
                "--format=csv,noheader,nounits"));
        if (out == null) {
            // Drivers older than about 510 have no compute_cap field and reject the whole query.
            out = run(List.of(
                    smi, "--query-gpu=index,uuid,name,memory.total,memory.free", "--format=csv,noheader,nounits"));
            return out == null ? List.of() : parse(out, false);
        }
        return parse(out, true);
    }

    /** Parses {@code nvidia-smi --format=csv,noheader,nounits} output; malformed lines are skipped. */
    static List<Gpu> parse(String csv, boolean withCapability) {
        List<Gpu> gpus = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("[")) {
                continue;
            }
            String[] f = t.split("\\s*,\\s*");
            int m = withCapability ? 4 : 3;
            if (f.length < m + 2) {
                continue;
            }
            try {
                int index = Integer.parseInt(f[0].strip());
                String uuid = f[1].strip();
                String name = f[2].strip();
                String cap = withCapability ? f[3].strip() : "";
                // memory reads [N/A] on some cards; that leaves the card listed, ranked by what is known
                gpus.add(new Gpu(index, uuid, name, cap, mebibytes(f[m]), mebibytes(f[m + 1])));
            } catch (NumberFormatException e) {
                // a line we do not understand is not a GPU we can pin
            }
        }
        return gpus;
    }

    private static int mebibytes(String field) {
        try {
            return (int) Math.round(Double.parseDouble(field.strip()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int capabilityValue(String computeCapability) {
        if (computeCapability == null || computeCapability.isBlank()) {
            return -1;
        }
        try {
            String[] p = computeCapability.strip().split("\\.");
            return Integer.parseInt(p[0]) * 10 + (p.length > 1 ? Integer.parseInt(p[1]) : 0);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ==================== Choosing ====================

    /** The preference, from the system property if set, else QuPath's preferences, else {@code auto}. */
    public static String preferredDevice() {
        String sys = System.getProperty(PREF_KEY);
        if (sys != null && !sys.isBlank()) {
            return sys.strip();
        }
        String v = preferredDeviceFromPrefs();
        return v == null || v.isBlank() ? AUTO : v.strip();
    }

    private static Preferences node() {
        return Preferences.userRoot().node(NODE);
    }

    /** Sets the shared preference ({@code auto}, an index or a UUID) for every Appose extension. */
    public static void setPreferredDevice(String value) {
        node().put(PREF_KEY, value == null || value.isBlank() ? AUTO : value.strip());
    }

    /**
     * The preference as a JavaFX property for a preference pane. Every Appose
     * extension's pane shows the same key; the property follows changes made
     * from any of them.
     */
    public static StringProperty preferenceProperty() {
        synchronized (LOCK) {
            if (preferenceProperty == null) {
                StringProperty p = new SimpleStringProperty(preferredDeviceFromPrefs());
                p.addListener((obs, old, now) -> {
                    String value = now == null || now.isBlank() ? AUTO : now.strip();
                    if (!value.equals(preferredDeviceFromPrefs())) {
                        setPreferredDevice(value);
                    }
                });
                try {
                    node().addPreferenceChangeListener(evt -> {
                        if (PREF_KEY.equals(evt.getKey())) {
                            String v = evt.getNewValue() == null ? AUTO : evt.getNewValue();
                            runOnFx(() -> {
                                if (!v.equals(p.get())) {
                                    p.set(v);
                                }
                            });
                        }
                    });
                } catch (RuntimeException e) {
                    // no listener: the pane may show a stale value until restart
                }
                preferenceProperty = p;
            }
            return preferenceProperty;
        }
    }

    private static String preferredDeviceFromPrefs() {
        try {
            return node().get(PREF_KEY, AUTO);
        } catch (RuntimeException e) {
            return AUTO;
        }
    }

    private static void runOnFx(Runnable r) {
        try {
            if (Platform.isFxApplicationThread()) {
                r.run();
            } else {
                Platform.runLater(r);
            }
        } catch (IllegalStateException e) {
            r.run();
        }
    }

    /** Label for the shared preference, identical in every extension's pane. */
    public static final String PREFERENCE_NAME = "GPU device (all Appose extensions)";

    /**
     * Values a preference pane offers: {@code auto} and indices 0 to 7. Fixed,
     * so building the pane never waits on {@code nvidia-smi}; the cards actually
     * present are logged when a worker starts.
     */
    public static List<String> preferenceChoices() {
        return List.of(AUTO, "0", "1", "2", "3", "4", "5", "6", "7");
    }

    /** Description for the shared preference, identical in every extension's pane. */
    public static String preferenceDescription() {
        return "Which NVIDIA GPU the Python workers of every Appose extension use, on machines with more "
                + "than one. 'auto' picks the card with the most free memory when the first worker starts "
                + "(on a desktop with a display card and a compute card, the compute card; on a shared "
                + "server, the least-loaded card). A number is the index nvidia-smi shows. The cards "
                + "found and the one chosen are written to the log when a worker starts. When QuPath is "
                + "started by a cluster scheduler that sets CUDA_VISIBLE_DEVICES, 'auto' keeps that "
                + "allocation. Takes effect for workers started after the change; restart QuPath to apply "
                + "it to a running one.";
    }

    /** One line per card, for a preference description or a log. */
    public static String describeAll() {
        List<Gpu> gpus = list();
        if (gpus.isEmpty()) {
            return "No NVIDIA GPU found.";
        }
        StringBuilder sb = new StringBuilder();
        for (Gpu g : gpus) {
            sb.append(g.describe()).append('\n');
        }
        return sb.toString().strip();
    }

    /** The card a worker should be pinned to, or empty to pin nothing. */
    public static Optional<Gpu> selected() {
        return select(list(), preferredDevice(), System.getenv("CUDA_VISIBLE_DEVICES"));
    }

    /**
     * The selection rule, without side effects.
     *
     * @param gpus          the cards present
     * @param preference    {@code auto}, an index, or a UUID
     * @param inheritedMask {@code CUDA_VISIBLE_DEVICES} as QuPath was started with, or null
     */
    static Optional<Gpu> select(List<Gpu> gpus, String preference, String inheritedMask) {
        if (gpus.isEmpty()) {
            return Optional.empty();
        }
        String pref = preference == null || preference.isBlank() ? AUTO : preference.strip();
        if (!AUTO.equalsIgnoreCase(pref)) {
            for (Gpu g : gpus) {
                if (pref.equalsIgnoreCase(g.uuid()) || pref.equals(String.valueOf(g.index()))) {
                    return Optional.of(g);
                }
            }
            logger.warn(
                    "The GPU preference '{}' matches none of the cards present; choosing automatically. Cards:\n{}",
                    pref,
                    describe(gpus));
        }
        if (inheritedMask != null && !inheritedMask.isBlank()) {
            return Optional.empty();
        }
        return gpus.stream()
                .sorted(Comparator.comparingInt(Gpu::freeGiB)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(Gpu::memoryMiB).reversed())
                        .thenComparing(
                                Comparator.comparingInt(Gpu::capabilityValue).reversed())
                        .thenComparingInt(Gpu::index))
                .findFirst();
    }

    private static String describe(List<Gpu> gpus) {
        StringBuilder sb = new StringBuilder();
        for (Gpu g : gpus) {
            sb.append("  ").append(g.describe()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ==================== Pinning ====================

    /** The variables that pin a worker to {@code gpu}. */
    static Map<String, String> pinVariables(Gpu gpu) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
        vars.put(
                "CUDA_VISIBLE_DEVICES",
                gpu.uuid() == null || gpu.uuid().isBlank() ? String.valueOf(gpu.index()) : gpu.uuid());
        return vars;
    }

    /**
     * {@code env} for a worker: isolated from QuPath's own Python and library
     * paths (see {@link #isolationVariables}), and pinned to the selected card
     * when there is one. Every worker, CPU or GPU, starts from this. Call
     * {@code python()} on the result.
     */
    public static Environment forWorker(Environment env) {
        if (env == null) {
            return null;
        }
        Optional<Gpu> gpu = selected();
        logDecisionOnce(gpu);
        Map<String, String> vars = new LinkedHashMap<>(isolationVariables(
                System.getenv(), envPrefix(env), isLinux(),
                dir -> Files.isRegularFile(Path.of(dir, "libcuda.so.1"))));
        gpu.ifPresent(g -> vars.putAll(pinVariables(g)));
        return new PinnedEnvironment(env, vars);
    }

    /**
     * The variables that keep a worker on what its environment's lock installed.
     * <ul>
     *   <li>{@code PYTHONNOUSERSITE=1}: otherwise Python puts
     *       {@code ~/.local/lib/pythonX.Y/site-packages} ahead of the environment's
     *       own, and a {@code pip install --user} of numpy, torch or opencv replaces
     *       the locked version inside the worker.</li>
     *   <li>{@code PYTHONPATH} and {@code PYTHONHOME} emptied (Python treats empty as
     *       unset) when QuPath was started with them.</li>
     *   <li>Linux, with a known prefix: {@code LD_LIBRARY_PATH} becomes the
     *       environment's {@code lib} folder, then only the inherited folders that
     *       hold the NVIDIA driver ({@code libcuda.so.1}; {@code /.singularity.d/libs}
     *       under Apptainer {@code --nv}). QuPath launch scripts add another
     *       environment's {@code torch/lib} and cuDNN for DJL; and inside an older
     *       container the system libstdc++ lacks the {@code CXXABI} versions
     *       conda-forge packages need, so the environment's own must load first.</li>
     * </ul>
     *
     * @param inherited   the variables QuPath runs with
     * @param envPrefix   the environment's prefix, or null when unknown
     * @param linux       whether {@code LD_LIBRARY_PATH} applies
     * @param isDriverDir true for a folder holding the NVIDIA driver
     * @return the variables to set on the worker
     */
    static Map<String, String> isolationVariables(Map<String, String> inherited, Path envPrefix,
                                                  boolean linux, Predicate<String> isDriverDir) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("PYTHONNOUSERSITE", "1");
        for (String name : List.of("PYTHONPATH", "PYTHONHOME")) {
            String value = inherited.get(name);
            if (value != null && !value.isBlank()) {
                logger.info("Not passing {}={} to the Python worker", name, value);
                vars.put(name, "");
            }
        }
        if (linux && envPrefix != null) {
            List<String> kept = new ArrayList<>();
            kept.add(envPrefix.resolve("lib").toString());
            List<String> dropped = new ArrayList<>();
            for (String dir : inherited.getOrDefault("LD_LIBRARY_PATH", "").split(":")) {
                if (dir.isBlank() || kept.contains(dir)) {
                    continue;
                }
                if (isDriverDir.test(dir)) {
                    kept.add(dir);
                } else {
                    dropped.add(dir);
                }
            }
            if (!dropped.isEmpty()) {
                logger.info("Not passing these LD_LIBRARY_PATH folders to the Python worker "
                        + "(no NVIDIA driver in them): {}", dropped);
            }
            // ":" whatever the host: LD_LIBRARY_PATH is a Linux variable.
            vars.put("LD_LIBRARY_PATH", String.join(":", kept));
        }
        return vars;
    }

    /** The environment's prefix: the parent of its {@code bin} folder, or null. */
    static Path envPrefix(Environment env) {
        List<String> bins = env.binPaths();
        if (bins == null || bins.isEmpty()) {
            return null;
        }
        Path bin = Path.of(bins.get(0));
        return "bin".equals(String.valueOf(bin.getFileName())) ? bin.getParent() : bin;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static void logDecisionOnce(Optional<Gpu> gpu) {
        synchronized (LOCK) {
            if (decisionLogged) {
                return;
            }
            decisionLogged = true;
        }
        List<Gpu> gpus = list();
        String inherited = System.getenv("CUDA_VISIBLE_DEVICES");
        if (gpus.isEmpty()) {
            logger.info("Appose GPU: no NVIDIA GPU found; Python workers use whatever PyTorch finds (MPS or CPU)");
        } else if (gpu.isEmpty()) {
            logger.info(
                    "Appose GPU: QuPath was started with CUDA_VISIBLE_DEVICES={}; keeping that allocation. "
                            + "Cards:\n{}",
                    inherited,
                    describe(gpus));
        } else {
            logger.info(
                    "Appose GPU: Python workers run on {} (preference '{}'). Cards:\n{}",
                    gpu.get().describe(),
                    preferredDevice(),
                    describe(gpus));
        }
    }

    /** An environment that adds variables to whatever the wrapped one passes its workers. */
    static final class PinnedEnvironment implements Environment {
        private final Environment delegate;
        private final Map<String, String> extra;

        PinnedEnvironment(Environment delegate, Map<String, String> extra) {
            this.delegate = delegate;
            this.extra = extra;
        }

        @Override
        public String base() {
            return delegate.base();
        }

        @Override
        public List<String> binPaths() {
            return delegate.binPaths();
        }

        @Override
        public List<String> launchArgs() {
            return delegate.launchArgs();
        }

        @Override
        public Map<String, String> envVars() {
            Map<String, String> merged = new LinkedHashMap<>(delegate.envVars());
            merged.putAll(extra);
            return merged;
        }

        @Override
        public Builder<?> builder() {
            return delegate.builder();
        }

        @Override
        public String type() {
            return delegate.type();
        }

        @Override
        public Environment activate(String name) throws BuildException {
            return new PinnedEnvironment(delegate.activate(name), extra);
        }

        @Override
        public Environment rebuild() throws BuildException {
            return new PinnedEnvironment(delegate.rebuild(), extra);
        }

        @Override
        public Environment delete() throws BuildException {
            return delegate.delete();
        }
    }

    // ==================== Checking what the worker got ====================

    /** What the worker's PyTorch reported about its GPU. */
    public record WorkerGpu(
            String torch,
            String cudaBuild,
            boolean cudaAvailable,
            String deviceName,
            String capability,
            String archs,
            boolean supported,
            String visible) {

        /** One line for the log. */
        public String describe() {
            if (torch == null || torch.isEmpty()) {
                return "no PyTorch in this environment";
            }
            if (!cudaAvailable) {
                return "PyTorch " + torch + " (CUDA build " + cudaBuild + ") sees no CUDA device";
            }
            return "PyTorch " + torch + " (CUDA " + cudaBuild + ") on cuda:0 = " + deviceName + " (compute "
                    + capability + "), CUDA_VISIBLE_DEVICES=" + (visible.isEmpty() ? "(unset)" : visible)
                    + (supported ? "" : "; NOT SUPPORTED by this build (built for " + archs + ")");
        }
    }

    private static final String CHECK_SCRIPT = String.join(
            "\n",
            "import os",
            "task.outputs['visible'] = os.environ.get('CUDA_VISIBLE_DEVICES', '')",
            "try:",
            "    import torch",
            "    task.outputs['torch'] = str(torch.__version__)",
            "    task.outputs['cuda_build'] = str(torch.version.cuda)",
            "    ok = bool(torch.cuda.is_available())",
            "    task.outputs['cuda_available'] = ok",
            "    if ok:",
            "        major, minor = torch.cuda.get_device_capability(0)",
            "        archs = list(torch.cuda.get_arch_list())",
            "        task.outputs['name'] = torch.cuda.get_device_name(0)",
            "        task.outputs['capability'] = '%d.%d' % (major, minor)",
            "        task.outputs['archs'] = ' '.join(archs)",
            "        sm = major * 10 + minor",
            "        cubin = any(a.startswith('sm_') and a[3:].isdigit() and int(a[3:]) // 10 == major"
                    + " and int(a[3:]) <= sm for a in archs)",
            "        ptx = any(a.startswith('compute_') and a[8:].isdigit() and int(a[8:]) <= sm for a in archs)",
            "        task.outputs['supported'] = bool(cubin or ptx)",
            "except ImportError:",
            "    task.outputs['torch'] = ''",
            "");

    /**
     * Asks a running worker which GPU its PyTorch uses and whether the build
     * supports it, and logs the answer: INFO when all is well, WARN with what to
     * do when not. Best effort; never throws.
     *
     * @return the report, or null when the check could not run
     */
    public static WorkerGpu checkWorker(Service worker, String label, Logger log) {
        Logger out = log != null ? log : logger;
        if (worker == null) {
            return null;
        }
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
        try {
            Task task = worker.task(CHECK_SCRIPT);
            task.waitFor();
            WorkerGpu r = new WorkerGpu(
                    str(task.outputs.get("torch")),
                    str(task.outputs.get("cuda_build")),
                    Boolean.TRUE.equals(task.outputs.get("cuda_available")),
                    str(task.outputs.get("name")),
                    str(task.outputs.get("capability")),
                    str(task.outputs.get("archs")),
                    Boolean.TRUE.equals(task.outputs.get("supported")),
                    str(task.outputs.get("visible")));
            if (r.torch().isEmpty()) {
                out.debug("{} GPU check: {}", label, r.describe());
            } else if (r.cudaAvailable() && !r.supported()) {
                out.warn(
                        "{} GPU check: {}. This PyTorch has no kernels for compute capability {}: GPU work will fail."
                                + " Pick another card in the 'Appose GPU device' preference, or use the CPU environment.",
                        label,
                        r.describe(),
                        r.capability());
            } else if (!r.cudaAvailable() && !list().isEmpty() && !"None".equals(r.cudaBuild())) {
                out.warn(
                        "{} GPU check: {}, although nvidia-smi lists {} card(s). The NVIDIA driver may be too old "
                                + "for CUDA {}; update it, or use the CPU environment.",
                        label,
                        r.describe(),
                        list().size(),
                        r.cudaBuild());
            } else {
                out.info("{} GPU check: {}", label, r.describe());
            }
            return r;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            out.warn("{} GPU check could not run: {}", label, e.getMessage());
            return null;
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ==================== nvidia-smi ====================

    private static String locateNvidiaSmi() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return null;
        }
        if (run(List.of("nvidia-smi", "-L")) != null) {
            return "nvidia-smi";
        }
        if (os.contains("win")) {
            String system32 = "C:\\Windows\\System32\\nvidia-smi.exe";
            if (run(List.of(system32, "-L")) != null) {
                return system32;
            }
        }
        return null;
    }

    /** Output of a command that exits 0 within 15 s, or null. */
    private static String run(List<String> command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
