package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.lib.common.GeneralTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages saving and loading clustering results within a QuPath project.
 * Results are stored as JSON under {@code <project>/qpcat/cluster_results/}.
 * Plot images are copied into a subdirectory alongside the JSON.
 */
public class ClusteringResultManager {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringResultManager.class);

    private static final String RESULTS_DIR = "qpcat/cluster_results";
    private static final String JSON_EXT = ".json";
    private static final String PLOTS_SUFFIX = "_plots";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ClusteringResultManager() {}

    /**
     * Get the results directory for a project, creating it if needed.
     */
    public static Path getResultsDirectory(Project<?> project) throws IOException {
        Path projectDir = project.getPath().getParent();
        Path resultsDir = projectDir.resolve(RESULTS_DIR);
        if (!Files.exists(resultsDir)) {
            Files.createDirectories(resultsDir);
            logger.info("Created clustering results directory: {}", resultsDir);
        }
        return resultsDir;
    }

    /**
     * List available saved result names (without extension), most recent first.
     */
    public static List<String> listResults(Project<?> project) throws IOException {
        Path resultsDir = getResultsDirectory(project);
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(resultsDir)) {
            files.filter(p -> p.toString().endsWith(JSON_EXT))
                    .sorted(Comparator.comparing((Path p) -> {
                        // Sort key must never be null: a single unreadable file would
                        // otherwise NPE the whole listing during comparison.
                        try { return Files.getLastModifiedTime(p); }
                        catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }).reversed())
                    .forEach(p -> {
                        String filename = p.getFileName().toString();
                        names.add(filename.substring(0, filename.length() - JSON_EXT.length()));
                    });
        }
        return names;
    }

    /**
     * Load summary information for all saved results (for display in chooser).
     * Returns map of name -> summary string.
     */
    public static Map<String, String> listResultSummaries(Project<?> project) throws IOException {
        Map<String, String> summaries = new LinkedHashMap<>();
        for (String name : listResults(project)) {
            try {
                SavedClusteringResult saved = loadSavedResult(project, name);
                String summary = saved.getTimestamp() != null
                        ? saved.getTimestamp().substring(0, Math.min(16, saved.getTimestamp().length()))
                        + " - " + saved.getSummary()
                        : saved.getSummary();
                summaries.put(name, summary);
            } catch (Exception e) {
                summaries.put(name, "(failed to read)");
            }
        }
        return summaries;
    }

    /**
     * Save a clustering result to the project (user-named, no scope metadata).
     * Copies plot images into the project directory so they persist.
     *
     * @return the sanitized base name actually used on disk
     */
    public static String saveResult(Project<?> project, String name,
                                     ClusteringResult result,
                                     String algorithm, String normalization,
                                     String embeddingMethod) throws IOException {
        return saveResult(project, name, result, algorithm, normalization, embeddingMethod,
                null, null, false);
    }

    /**
     * Save a clustering result to the project, recording its scope and origin.
     * Copies plot images into the project directory so they persist.
     *
     * @param scopeKey   source image id for single-image runs, or
     *                   {@link SavedClusteringResult#PROJECT_SCOPE_KEY} for
     *                   project-wide runs (may be null on older callers)
     * @param scopeLabel human-readable scope (image name or "Entire project")
     * @param autoSaved  true when persisted automatically at end of run
     * @return the sanitized base name actually used on disk
     */
    public static String saveResult(Project<?> project, String name,
                                     ClusteringResult result,
                                     String algorithm, String normalization,
                                     String embeddingMethod,
                                     String scopeKey, String scopeLabel,
                                     boolean autoSaved) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IOException("Result name cannot be empty");
        }

        Path resultsDir = getResultsDirectory(project);
        String safeName = GeneralTools.stripInvalidFilenameChars(name.trim());
        if (safeName.isEmpty()) safeName = "result";

        // Copy plot images to project directory
        Map<String, String> persistedPlots = null;
        if (result.getPlotPaths() != null && !result.getPlotPaths().isEmpty()) {
            Path plotsDir = resultsDir.resolve(safeName + PLOTS_SUFFIX);
            if (!Files.exists(plotsDir)) Files.createDirectories(plotsDir);

            persistedPlots = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : result.getPlotPaths().entrySet()) {
                Path srcPath = Path.of(entry.getValue());
                if (Files.exists(srcPath)) {
                    String plotFilename = entry.getKey() + getFileExtension(srcPath);
                    Path destPath = plotsDir.resolve(plotFilename);
                    Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
                    // Store relative path from results directory
                    persistedPlots.put(entry.getKey(),
                            safeName + PLOTS_SUFFIX + "/" + plotFilename);
                }
            }
        }

        // Build the saved result
        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                result, name, algorithm, normalization, embeddingMethod);

        // Replace temp plot paths with persisted relative paths
        if (persistedPlots != null) {
            saved.setPlotPaths(persistedPlots);
        }

        // Scope + origin
        saved.setScopeKey(scopeKey);
        saved.setScopeLabel(scopeLabel);
        saved.setAutoSaved(autoSaved);

        // Snapshot the current cluster palette (the "Cluster N" PathClass colors,
        // which are the source of truth) so reopening restores the user's colors.
        saved.setClusterColors(snapshotClusterColors(result.getClusterLabels()));

        // Write JSON atomically so a mid-write failure cannot corrupt an existing result.
        Path file = resultsDir.resolve(safeName + JSON_EXT);
        String json = GSON.toJson(saved);
        writeStringAtomic(file, json);
        logger.info("Saved clustering result '{}' to {} ({} clusters, {} cells, {})",
                name, file, result.getNClusters(), result.getNCells(),
                autoSaved ? "auto" : "named");
        return safeName;
    }

    /**
     * Snapshot the color of each distinct cluster's "Cluster N" PathClass. Keyed
     * by class name -> packed 0xRRGGBB. Returns null when there are no non-noise
     * labels (e.g. embedding-only runs), so older-style saves are unchanged.
     */
    private static Map<String, Integer> snapshotClusterColors(int[] labels) {
        if (labels == null) return null;
        Map<String, Integer> colors = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (int lab : labels) {
            if (lab < 0 || !seen.add(lab)) continue;
            String name = "Cluster " + lab;
            Integer rgb = PathClass.fromString(name).getColor();
            if (rgb != null) colors.put(name, rgb);
        }
        return colors.isEmpty() ? null : colors;
    }

    /**
     * Write {@code content} to {@code file} atomically: write a sibling temp file, flush,
     * then move it into place. A crash or exception mid-write leaves the previous good
     * file intact instead of a truncated/empty JSON (a plain {@code Files.writeString}
     * truncates first, so an interruption destroys the existing result). Falls back to a
     * non-atomic replace only if the filesystem rejects ATOMIC_MOVE.
     */
    private static void writeStringAtomic(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort temp cleanup
            }
            throw e;
        }
    }

    private static final DateTimeFormatter AUTO_NAME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Auto-save a freshly computed result with a generated, scope-tagged name
     * (e.g. {@code auto_20260617_193235_leiden}). Called at the end of every
     * clustering / embedding run so results are always reloadable via
     * "View Past Results" without the user clicking Save.
     *
     * @param scopeKey   source image id, or
     *                   {@link SavedClusteringResult#PROJECT_SCOPE_KEY}
     * @param scopeLabel human-readable scope
     * @return the sanitized base name actually used on disk
     */
    public static String saveResultAuto(Project<?> project, ClusteringResult result,
                                        String algorithm, String normalization,
                                        String embeddingMethod,
                                        String scopeKey, String scopeLabel) throws IOException {
        String algoTag = algorithm == null ? "result"
                : algorithm.toLowerCase().replaceAll("[^a-z0-9]+", "");
        if (algoTag.isEmpty()) algoTag = "result";
        String name = "auto_" + LocalDateTime.now().format(AUTO_NAME_FMT) + "_" + algoTag;
        return saveResult(project, name, result, algorithm, normalization, embeddingMethod,
                scopeKey, scopeLabel, true);
    }

    /**
     * Count saved results whose scopeKey matches (null-safe). Used to warn the
     * user when a single image / the project accumulates more than a handful.
     */
    public static int countResultsForScope(Project<?> project, String scopeKey)
            throws IOException {
        int count = 0;
        for (String name : listResults(project)) {
            try {
                SavedClusteringResult saved = loadSavedResult(project, name);
                if (Objects.equals(scopeKey, saved.getScopeKey())) count++;
            } catch (Exception e) {
                // ignore unreadable entries for counting
            }
        }
        return count;
    }

    /**
     * On-disk size in bytes of one saved result (its JSON plus its plots dir).
     */
    public static long resultSize(Project<?> project, String name) throws IOException {
        Path resultsDir = getResultsDirectory(project);
        long size = 0;
        Path json = resultsDir.resolve(name + JSON_EXT);
        if (Files.exists(json)) size += Files.size(json);
        size += dirSize(resultsDir.resolve(name + PLOTS_SUFFIX));
        return size;
    }

    /**
     * Total on-disk size in bytes of the whole cluster_results directory.
     */
    public static long totalResultsSize(Project<?> project) throws IOException {
        return dirSize(getResultsDirectory(project));
    }

    private static long dirSize(Path dir) {
        if (dir == null || !Files.exists(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0; }
                    }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Human-readable byte size using ASCII units (B, KB, MB, GB).
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    /**
     * Lightweight listing of every saved result for the Manage dialog:
     * name, timestamp, summary, scope label, origin, and on-disk size.
     * Ordered most-recent first (same order as {@link #listResults}).
     */
    public static List<ResultEntry> listResultEntries(Project<?> project) throws IOException {
        List<ResultEntry> entries = new ArrayList<>();
        for (String name : listResults(project)) {
            ResultEntry e = new ResultEntry();
            e.name = name;
            try {
                SavedClusteringResult saved = loadSavedResult(project, name);
                e.timestamp = saved.getTimestamp() != null
                        ? saved.getTimestamp().substring(0,
                                Math.min(16, saved.getTimestamp().length()))
                        : "";
                e.summary = saved.getSummary();
                e.scopeLabel = saved.getScopeLabel();
                e.autoSaved = saved.isAutoSaved();
            } catch (Exception ex) {
                e.summary = "(failed to read)";
            }
            try {
                e.sizeBytes = resultSize(project, name);
            } catch (Exception ex) {
                e.sizeBytes = 0;
            }
            entries.add(e);
        }
        return entries;
    }

    /**
     * Lightweight row for the Manage Saved Results dialog.
     */
    public static class ResultEntry {
        public String name;
        public String timestamp = "";
        public String summary = "";
        public String scopeLabel;
        public boolean autoSaved;
        public long sizeBytes;
    }

    /**
     * Load a saved result and return it as a ClusteringResult for display.
     * Resolves relative plot paths back to absolute paths.
     */
    public static ClusteringResult loadResult(Project<?> project, String name) throws IOException {
        SavedClusteringResult saved = loadSavedResult(project, name);
        Path resultsDir = getResultsDirectory(project);

        // Resolve relative plot paths to absolute
        if (saved.getPlotPaths() != null) {
            Map<String, String> absolutePaths = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : saved.getPlotPaths().entrySet()) {
                Path absPath = resultsDir.resolve(entry.getValue());
                if (Files.exists(absPath)) {
                    absolutePaths.put(entry.getKey(), absPath.toString());
                } else {
                    logger.warn("Plot file not found: {}", absPath);
                }
            }
            saved.setPlotPaths(absolutePaths);
        }

        // Restore the saved cluster palette to the "Cluster N" PathClasses so the
        // reopened result's plots and the viewer overlay show the colors the user
        // had when it was saved (PathClass is the single source of truth).
        new ResultApplier().applyClusterColors(saved.getClusterColors());

        return saved.toClusteringResult();
    }

    /**
     * Re-snapshot the current "Cluster N" palette (from the live PathClasses) and
     * write it into an existing saved result's JSON, so color edits made while a
     * result is open stick to that result file instead of only living on the
     * PathClasses / regenerated PNGs. No-op if the result file or palette is absent.
     */
    public static void persistCurrentPalette(Project<?> project, String name, int[] labels)
            throws IOException {
        Map<String, Integer> colors = snapshotClusterColors(labels);
        if (colors == null) return;
        Path file = getResultsDirectory(project).resolve(name + JSON_EXT);
        if (!Files.exists(file)) return;
        SavedClusteringResult saved = GSON.fromJson(Files.readString(file),
                SavedClusteringResult.class);
        if (saved == null) {
            throw new IOException("Saved result '" + name + "' is empty or corrupt; "
                    + "cannot update its palette.");
        }
        saved.setClusterColors(colors);
        // Atomic: never truncate a good result file just to rewrite its palette.
        writeStringAtomic(file, GSON.toJson(saved));
        logger.info("Updated saved palette for result '{}'", name);
    }

    /**
     * Load the raw saved result (with metadata) without path resolution.
     */
    public static SavedClusteringResult loadSavedResult(Project<?> project,
                                                         String name) throws IOException {
        Path resultsDir = getResultsDirectory(project);
        Path file = resultsDir.resolve(name + JSON_EXT);

        if (!Files.exists(file)) {
            throw new IOException("Result file not found: " + file);
        }

        String json = Files.readString(file);
        SavedClusteringResult saved = GSON.fromJson(json, SavedClusteringResult.class);
        if (saved == null) {
            throw new IOException("Result file is empty or corrupt: " + file);
        }
        logger.info("Loaded clustering result '{}' from {}", name, file);
        return saved;
    }

    /**
     * Delete a saved result and its plot directory.
     */
    public static void deleteResult(Project<?> project, String name) throws IOException {
        Path resultsDir = getResultsDirectory(project);
        String safeName = name;  // already sanitized when saved
        Path file = resultsDir.resolve(safeName + JSON_EXT);
        Path plotsDir = resultsDir.resolve(safeName + PLOTS_SUFFIX);

        // Delete plots directory
        if (Files.exists(plotsDir)) {
            try (Stream<Path> plotFiles = Files.list(plotsDir)) {
                plotFiles.forEach(p -> {
                    try { Files.delete(p); }
                    catch (IOException e) { logger.warn("Failed to delete plot: {}", p); }
                });
            }
            Files.delete(plotsDir);
        }

        // Delete JSON
        if (Files.exists(file)) {
            Files.delete(file);
            logger.info("Deleted clustering result '{}'", name);
        }
    }

    private static String getFileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : ".png";
    }
}
