package qupath.ext.qpcat.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Predicts whether a clustering configuration can actually run on this machine,
 * so a doomed run is refused at configuration time instead of discovered as an
 * out-of-memory kill or an overnight hang.
 *
 * <p><b>Why a model rather than a fixed cell-count cap.</b> "Too many cells" is
 * not a property of the data alone -- agglomerative clustering that dies at
 * 50,000 cells in 8 GB is comfortable at 200,000 in 512 GB. Every limit here is
 * therefore a predicted peak allocation compared against the memory this machine
 * actually has. The thresholds are ratios of available RAM, not magic numbers.
 *
 * <p><b>The coefficients are measured, not guessed.</b> Each formula was fitted
 * against a ladder run in the real QP-CAT Appose environment (16 cores, 20
 * features, 20 clusters, peak RSS sampled from {@code /proc}, 6 GB cap). The
 * measurements and the fitted predictions:
 *
 * <pre>
 *   agglomerative   20,000 cells -> 3.17 GB measured, 3.17 GB predicted
 *                   50,000 cells -> OOM at the 6 GB cap (18.8 GB predicted)
 *   co-occurrence   20,000 cells -> 2.27 GB measured, 2.39 GB predicted
 *                   50,000 cells -> 4.47 GB measured, 4.63 GB predicted
 *                  100,000 cells -> OOM at the 6 GB cap
 *   Ripley L       100,000 cells -> 1.50 GB measured, 1.50 GB predicted
 *                  250,000 cells -> 5.19 GB measured, 5.19 GB predicted
 *   HDBSCAN         50,000 cells -> 34.4 s measured, 34.5 s predicted
 *                  100,000 cells -> 151  s measured, 151  s predicted
 * </pre>
 *
 * <p>Methods deliberately absent from the hazard list, because the same ladder
 * showed them flat to 250,000 cells: KMeans (2.3 s / 0.25 GB), MiniBatch KMeans
 * (1.4 s / 0.22 GB), GMM (1.9 s / 0.30 GB). Leiden is linear but not free
 * (140 s / 4.87 GB at 250,000), so it warns on memory only.
 *
 * <p>Pure and dependency-free by design: no JavaFX, no QuPath types, no I/O. The
 * GUI, the YAML batch validator and the tests all consult this one table, so a
 * threshold can only ever be changed in one place.
 */
public final class ScalingLimits {

    private static final Logger logger = LoggerFactory.getLogger(ScalingLimits.class);

    private ScalingLimits() {}

    /** Predicted peak is this fraction of RAM or more: the run cannot succeed. */
    private static final double BLOCK_RAM_FRACTION = 0.85;
    /** Predicted peak is this fraction of RAM or more: it will likely thrash. */
    private static final double WARN_RAM_FRACTION = 0.50;
    /**
     * On a machine whose memory we could not read, stay silent below this
     * prediction. Any machine that can run QuPath plus the Appose Python stack
     * has this much; below it the warning carries no information.
     */
    private static final double UNKNOWN_RAM_REPORT_FLOOR_GB = 4.0;
    /** Predicted runtime at or above this is refused (2 hours). */
    private static final double BLOCK_SECONDS = 7200;
    /** Predicted runtime at or above this is flagged (10 minutes). */
    private static final double WARN_SECONDS = 600;

    private static final double GB = 1024.0 * 1024.0 * 1024.0;
    /** Baseline interpreter + loaded-library footprint, measured at ~0.19 GB. */
    private static final double BASE_GB = 0.2;

    public enum Severity {
        /** Nothing to say. */
        OK,
        /** Will probably complete, but slowly or close to the memory ceiling. */
        WARN,
        /** Cannot complete on this machine. Refuse it. */
        BLOCK
    }

    /**
     * One predicted problem. {@code remedy} is the whole point -- a block that
     * does not say what to do instead just moves the dead end.
     */
    public record Finding(
            Severity severity,
            String subject,
            String why,
            String remedy,
            double predictedPeakGb,
            double predictedSeconds) {

        /** Single-line rendering for logs, YAML errors and dialog text. */
        public String describe() {
            StringBuilder sb = new StringBuilder(subject).append(": ").append(why);
            if (predictedPeakGb > 0)
                sb.append(" (needs about ").append(formatGb(predictedPeakGb)).append(")");
            else if (predictedSeconds > 0)
                sb.append(" (about ").append(humanDuration(predictedSeconds)).append(")");
            sb.append(" ").append(remedy);
            return sb.toString();
        }
    }

    /**
     * Human-readable size. "%.0f GB" rendered a 0.43 GB prediction as "0 GB",
     * which reads as a bug rather than as "small".
     */
    public static String formatGb(double gb) {
        if (gb < 1.0) return String.format("%d MB", Math.max(1L, Math.round(gb * 1024)));
        if (gb < 10.0) return String.format("%.1f GB", gb);
        return String.format("%.0f GB", gb);
    }

    /** Everything the prediction depends on. Spatial fields may be left unset. */
    public static final class Request {
        public ClusteringConfig.Algorithm algorithm = ClusteringConfig.Algorithm.LEIDEN;
        public long nCells;
        public int nFeatures = 20;
        public int nNeighbors = 50;
        /** True cluster count when known; otherwise an estimate. */
        public int nClusters = DEFAULT_ASSUMED_CLUSTERS;
        /** Set when nClusters came from a finished run rather than a guess. */
        public boolean clusterCountIsExact = false;
        /** Largest single cluster; 0 means "assume balanced". */
        public long largestClusterSize = 0;
        /**
         * Cells in the largest INDEPENDENT AREA; 0 means "one area", i.e. the
         * whole run.
         * <p>
         * Ripley's L and co-occurrence run once per area, so their cost is set
         * by the biggest area rather than by the cohort. On a 55-core TMA that
         * is roughly a fiftieth of the total, and co-occurrence's model is
         * linear in the cell count -- keying it on the total predicts an
         * allocation that never happens and refuses runs that comfortably fit.
         */
        public long largestAreaCells = 0;
        public boolean ripley = false;
        public boolean coOccurrence = false;
        public int coOccurrenceIntervals = DEFAULT_COOC_INTERVALS;
        /** Physical RAM budget in GB; <= 0 asks this machine. */
        public double availableRamGb = 0;

        public Request() {}

        public Request(ClusteringConfig.Algorithm algorithm, long nCells, int nFeatures) {
            this.algorithm = algorithm;
            this.nCells = nCells;
            this.nFeatures = nFeatures;
        }
    }

    /**
     * With no clustering run yet there is no cluster count, but the spatial
     * memory model is driven by k^2. Twenty is the middle of what QP-CAT
     * actually produces on multiplex panels and is used only for the advisory
     * pre-flight -- the authoritative check re-runs with the real count.
     */
    public static final int DEFAULT_ASSUMED_CLUSTERS = 20;

    /** squidpy's co-occurrence default, and QP-CAT's. */
    public static final int DEFAULT_COOC_INTERVALS = 50;

    // ---- Calibrated cost models -------------------------------------------

    /**
     * scikit-learn's Ward linkage without a connectivity constraint materializes
     * the condensed pairwise distance matrix, then copies it -- so the peak is
     * about {@code 2 * N^2/2 * 8} bytes. Fitted exactly at 10k and 20k cells.
     */
    public static double agglomerativePeakGb(long nCells) {
        double n = (double) nCells;
        return BASE_GB + (n * n * 8.0) / GB;
    }

    /**
     * squidpy 1.6.6's {@code _occur_count} allocates
     * {@code (nCells, intervals * nClusters^2)} int32 up front. Note that
     * {@code n_splits} no longer bounds this -- in 1.6.6 it survives only in a
     * log message, so squidpy's own memory guard is gone.
     */
    public static double coOccurrencePeakGb(long nCells, int nClusters, int intervals) {
        double k = Math.max(1, nClusters);
        return BASE_GB + 0.75  // squidpy + anndata import footprint, measured
                + ((double) nCells * intervals * k * k * 4.0) / GB;
    }

    /**
     * Co-occurrence is quadratic in TIME as well as memory: the numba kernel
     * compares every cell against every other, at every distance interval. On a
     * big-memory machine the allocation fits and this becomes the binding
     * constraint instead. Anchored on the measured 50k point (15.3 s wall,
     * ~12 s of it compute, on 16 cores).
     */
    public static double coOccurrenceSeconds(long nCells) {
        double scale = nCells / 50_000.0;
        return 12.0 * scale * scale;
    }

    /**
     * Ripley's L calls {@code pdist} on the observed cells of each cluster in
     * turn, so the peak is set by the LARGEST cluster, not the total. squidpy's
     * {@code n_observations} caps only the simulated patterns. Coefficient
     * fitted at 100k and 250k cells.
     */
    public static double ripleyPeakGb(long largestClusterSize) {
        double m = (double) largestClusterSize;
        return BASE_GB + 0.6 + 2.68e-8 * m * m;
    }

    /**
     * HDBSCAN's memory stays flat (0.27 GB at 250k) but its time grows about
     * N^2.13 -- 34 s at 50k, 151 s at 100k, still running at 250k. Anchored on
     * the measured 100k point.
     */
    public static double hdbscanSeconds(long nCells) {
        return 151.0 * Math.pow(nCells / 100_000.0, 2.13);
    }

    /**
     * Leiden peak RSS. Measured 2026-08-05 in the QP-CAT env, 20 features:
     * 50k/k=15 0.87 GB, 50k/k=50 1.51, 100k/k=15 1.51, 100k/k=50 2.56,
     * 200k/k=50 4.19. The stored graph is only ~21 bytes/edge; the rest is
     * pynndescent's transient candidate arrays, which is why this scales with
     * n_neighbors far more steeply than the final CSR would suggest.
     */
    public static double leidenPeakGb(long nCells, int nNeighbors) {
        return BASE_GB + (double) nCells * nNeighbors * 3.52e-7;
    }

    /**
     * Cells the per-area spatial statistics actually process at once: the
     * largest independent area, or the whole run when there is only one.
     */
    private static long spatialUnitCells(Request r) {
        return r.largestAreaCells > 0 ? Math.min(r.largestAreaCells, r.nCells) : r.nCells;
    }

    /**
     * Largest cluster, assuming roughly balanced clusters when unknown.
     * <p>
     * With independent areas the relevant figure is the largest cluster
     * <i>within one area</i>, because Ripley runs per area. When the true
     * per-area cluster size is unknown it is estimated from the largest area
     * rather than the cohort -- otherwise a 55-core TMA is judged as though
     * every core's cluster held every cell of that cluster on the slide.
     */
    private static long assumedLargestCluster(Request r) {
        long unit = spatialUnitCells(r);
        if (r.largestClusterSize > 0) {
            // A measured cohort-wide cluster cannot be bigger than the area it
            // is measured in once the run is partitioned.
            return Math.min(r.largestClusterSize, unit);
        }
        return (long) Math.ceil((double) unit / Math.max(1, r.nClusters));
    }

    // ---- The check ---------------------------------------------------------

    /**
     * Physical RAM on this machine in GB, or <b>empty when it cannot be
     * measured</b>.
     *
     * <p>There is deliberately no fallback number. RAM is a physical property of
     * the user's machine; inventing a value means refusing to run against a
     * figure we made up. That is exactly what happened on a packaged QuPath
     * runtime where {@code com.sun.management} is not resolvable: detection fell
     * through to a hardcoded 8 GB and blocked a 430k-cell Leiden run that the
     * user had run before. Empty means "unknown", and unknown must never
     * block.</p>
     */
    public static OptionalDouble detectRamGb() {
        OptionalDouble v = ramFromMxBean();
        if (v.isPresent()) return v;
        v = ramFromProcMeminfo();
        if (v.isPresent()) return v;
        v = ramFromCommand();
        if (v.isPresent()) return v;
        logger.warn("Could not determine total system memory by any method; "
                + "scale checks will report predictions without judging them");
        return OptionalDouble.empty();
    }

    /**
     * The management bean, read REFLECTIVELY rather than through an
     * {@code instanceof com.sun.management.OperatingSystemMXBean} cast.
     *
     * <p>The cast is what failed in the field: the interface lives in the
     * {@code jdk.management} module, and whether it resolves depends on the
     * runtime image and on which classloader the extension was loaded by --
     * neither of which is a property of the machine. The object itself still
     * carries the method. Also tries the pre-JDK-14 name.</p>
     */
    private static OptionalDouble ramFromMxBean() {
        try {
            Object os = ManagementFactory.getOperatingSystemMXBean();
            for (String name : new String[]{"getTotalMemorySize", "getTotalPhysicalMemorySize"}) {
                try {
                    var m = os.getClass().getMethod(name);
                    m.setAccessible(true);
                    Object result = m.invoke(os);
                    if (result instanceof Number n && n.longValue() > 0) {
                        return OptionalDouble.of(n.longValue() / GB);
                    }
                } catch (Exception ignored) {
                    // Try the next name.
                }
            }
        } catch (Throwable ignored) {
            // No management interface at all.
        }
        return OptionalDouble.empty();
    }

    /** Linux (including WSL): the kernel's own figure, no JDK classes involved. */
    private static OptionalDouble ramFromProcMeminfo() {
        Path meminfo = Path.of("/proc/meminfo");
        if (!Files.isReadable(meminfo)) return OptionalDouble.empty();
        try (var lines = Files.lines(meminfo)) {
            var total = lines.filter(l -> l.startsWith("MemTotal:")).findFirst();
            if (total.isPresent()) {
                String[] parts = total.get().trim().split("\\s+");
                if (parts.length >= 2) {
                    // MemTotal is in kB.
                    long kb = Long.parseLong(parts[1]);
                    if (kb > 0) return OptionalDouble.of(kb * 1024.0 / GB);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read /proc/meminfo: {}", e.getMessage());
        }
        return OptionalDouble.empty();
    }

    /** macOS and Windows last resort: ask the OS directly. */
    private static OptionalDouble ramFromCommand() {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        List<String> cmd;
        if (osName.contains("mac")) {
            cmd = List.of("sysctl", "-n", "hw.memsize");            // bytes
        } else if (osName.contains("win")) {
            cmd = List.of("wmic", "ComputerSystem", "get", "TotalPhysicalMemory");
        } else {
            return OptionalDouble.empty();
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            for (String tok : out.split("\\s+")) {
                if (tok.matches("\\d{9,}")) {   // a byte count, not a header word
                    return OptionalDouble.of(Long.parseLong(tok) / GB);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read total memory via {}: {}", cmd.get(0), e.getMessage());
        }
        return OptionalDouble.empty();
    }

    /**
     * Every predicted problem with this configuration, worst first. Empty means
     * nothing to say -- which is the answer for every method except the four
     * that measurably break.
     */
    public static List<Finding> check(Request r) {
        List<Finding> out = new ArrayList<>();
        OptionalDouble ram = r.availableRamGb > 0
                ? OptionalDouble.of(r.availableRamGb) : detectRamGb();
        if (r.nCells <= 0) return out;

        switch (r.algorithm) {
            case AGGLOMERATIVE -> {
                double gb = agglomerativePeakGb(r.nCells);
                addMemory(out, gb, ram,
                        "Agglomerative clustering of " + fmt(r.nCells) + " cells",
                        "hierarchical clustering has to hold every pairwise distance in memory "
                                + "at once, which grows with the SQUARE of the cell count",
                        "Use Leiden (graph-based) for large panels, or MiniBatch KMeans if you "
                                + "need a fixed number of clusters. Both handle millions of cells.");
            }
            case HDBSCAN -> {
                double sec = hdbscanSeconds(r.nCells);
                addTime(out, sec,
                        "HDBSCAN on " + fmt(r.nCells) + " cells",
                        "HDBSCAN's runtime grows about as the square of the cell count",
                        "Use Leiden, which is graph-based and scales roughly linearly.");
            }
            case LEIDEN -> {
                double gb = leidenPeakGb(r.nCells, r.nNeighbors);
                addMemory(out, gb, ram,
                        "Leiden on " + fmt(r.nCells) + " cells with n_neighbors="
                                + r.nNeighbors,
                        "the nearest-neighbour graph holds n_neighbors entries per cell",
                        "Lower n_neighbors (15 is a common default) to shrink the graph "
                                + "proportionally.");
            }
            default -> {
                // KMeans, MiniBatch KMeans, GMM, BANKSY and None measured flat
                // to 250,000 cells. Nothing to predict.
            }
        }

        if (r.coOccurrence) {
            // Per area, not per run: co-occurrence is computed once per
            // independent area, so the peak is set by the biggest one.
            long unit = spatialUnitCells(r);
            double gb = coOccurrencePeakGb(unit, r.nClusters, r.coOccurrenceIntervals);
            // Time, unlike memory, still accumulates across areas.
            double sec = coOccurrenceSeconds(r.nCells);
            String basis = r.clusterCountIsExact
                    ? r.nClusters + " clusters"
                    : "an assumed " + r.nClusters + " clusters";
            String scope = unit == r.nCells
                    ? fmt(r.nCells) + " cells"
                    : fmt(unit) + " cells in the largest area";
            addCost(out, gb, sec, ram,
                    "Co-occurrence on " + scope + " (" + basis + ")",
                    "co-occurrence allocates one counter per cell per distance interval per "
                            + "CLUSTER PAIR, and compares every cell against every other one",
                    "Turn off co-occurrence, or reduce the number of clusters. Neighbourhood "
                            + "enrichment answers a similar question at a fraction of the cost.");
        }

        if (r.ripley) {
            long m = assumedLargestCluster(r);
            double gb = ripleyPeakGb(m);
            addMemory(out, gb, ram,
                    "Ripley's L with a largest cluster of " + fmt(m) + " cells",
                    "Ripley's L computes every pairwise distance WITHIN each cluster, so the "
                            + "largest cluster sets the peak",
                    "Turn off Ripley, or use neighbourhood enrichment instead.");
        }

        out.sort((a, b) -> b.severity.compareTo(a.severity));
        return out;
    }

    /** True when any finding would refuse the run. */
    public static boolean isBlocked(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.BLOCK);
    }

    /** Visible for tests: the unknown-machine path is the one that must not block. */
    static void addMemoryForTest(List<Finding> out, double gb, OptionalDouble ram,
                                 String subject, String why, String remedy) {
        addMemory(out, gb, ram, subject, why, remedy);
    }

    private static void addMemory(List<Finding> out, double gb, OptionalDouble ram,
                                  String subject, String why, String remedy) {
        if (ram.isEmpty()) {
            // Unknown machine: say what the run is predicted to need and let the
            // user decide. Refusing on the strength of a number we do not have
            // would stop work the machine may handle perfectly well.
            //
            // Below the reporting floor, say nothing at all. This is a policy
            // choice about when to speak -- not a claim about the machine: a
            // prediction smaller than QuPath's own working set cannot be the
            // thing that runs a machine out of memory, so mentioning it is pure
            // noise. (A 13,286-cell Leiden needs ~0.4 GB and warned.)
            if (gb < UNKNOWN_RAM_REPORT_FLOOR_GB) return;
            out.add(new Finding(Severity.WARN, subject, why,
                    "QP-CAT could not read this machine's total memory, so it cannot judge "
                            + "whether that fits -- check it against what you know this machine "
                            + "has. If it is close to the limit, " + remedy,
                    gb, 0));
            return;
        }
        double total = ram.getAsDouble();
        Severity sev = gb >= total * BLOCK_RAM_FRACTION ? Severity.BLOCK
                : gb >= total * WARN_RAM_FRACTION ? Severity.WARN
                : Severity.OK;
        if (sev == Severity.OK) return;
        String tail = sev == Severity.BLOCK
                ? String.format("This machine has %.0f GB.", total)
                : String.format("This machine has %.0f GB, so it may swap.", total);
        out.add(new Finding(sev, subject, why, tail + " " + remedy, gb, 0));
    }

    /**
     * For work that can be bound by either memory or time, report whichever is
     * worse -- and when they tie, memory, because an OOM kill is the failure the
     * user cannot recover from mid-run.
     */
    private static void addCost(List<Finding> out, double gb, double sec, OptionalDouble ram,
                                String subject, String why, String remedy) {
        List<Finding> mem = new ArrayList<>();
        addMemory(mem, gb, ram, subject, why, remedy);
        List<Finding> time = new ArrayList<>();
        addTime(time, sec, subject, why, remedy);

        Severity memSev = mem.isEmpty() ? Severity.OK : mem.get(0).severity();
        Severity timeSev = time.isEmpty() ? Severity.OK : time.get(0).severity();
        if (memSev == Severity.OK && timeSev == Severity.OK) return;
        out.add(memSev.compareTo(timeSev) >= 0 ? mem.get(0) : time.get(0));
    }

    private static void addTime(List<Finding> out, double sec,
                                String subject, String why, String remedy) {
        Severity sev = sec >= BLOCK_SECONDS ? Severity.BLOCK
                : sec >= WARN_SECONDS ? Severity.WARN
                : Severity.OK;
        if (sev == Severity.OK) return;
        out.add(new Finding(sev, subject, why, remedy, 0, sec));
    }

    static String humanDuration(double seconds) {
        if (seconds < 90) return String.format("%.0f seconds", seconds);
        if (seconds < 5400) return String.format("%.0f minutes", seconds / 60.0);
        return String.format("%.1f hours", seconds / 3600.0);
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }
}
