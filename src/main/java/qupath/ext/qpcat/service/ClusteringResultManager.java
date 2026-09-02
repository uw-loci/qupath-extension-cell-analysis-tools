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

    // serializeSpecialFloatingPointValues: spatial stats can legitimately
    // produce NaN (e.g. Geary's C / Moran's I for a zero-variance marker when a
    // cell type is entirely absent from an image). Without this, Gson throws
    // "NaN is not a valid double" and the whole result fails to save. NaN is
    // preserved honestly (undefined stat), and is read back by both Gson and
    // Python's json module.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeSpecialFloatingPointValues()
            .create();

    private ClusteringResultManager() {}

    /**
     * Scalar-only view of a saved result, used for listing / counting without
     * paying to deserialize the large {@code clusterLabels} / {@code embedding} /
     * per-cell arrays. Gson skips any JSON field absent from this class, so
     * parsing a result file into a {@code Meta} allocates none of those arrays --
     * turning listing from O(results x filesize) allocations into a cheap scan.
     */
    private static final class Meta {
        String timestamp;
        String algorithm;
        int nClusters;
        int nCells;
        String scopeKey;
        String derivedFrom;
        String derivedOp;

        String summary() {
            return nClusters + " clusters, " + nCells + " cells"
                    + (algorithm != null ? " (" + algorithm + ")" : "")
                    // Show the edit chain, so a list of five near-identical names
                    // reads as a history instead of five unrelated results.
                    + (derivedFrom != null && !derivedFrom.isBlank()
                        ? " [" + (derivedOp != null ? derivedOp : "edit")
                          + " of " + derivedFrom + "]"
                        : "");
        }
    }

    /** Parse only the scalar metadata of a saved result (skips the big arrays). */
    private static Meta loadMeta(Project<?> project, String name) throws IOException {
        Path file = getResultsDirectory(project).resolve(name + JSON_EXT);
        if (!Files.exists(file)) {
            throw new IOException("Result file not found: " + file);
        }
        // Stream the parse. Files.readString would pull the WHOLE result file into
        // a String first -- for a million-cell result that is hundreds of MB, which
        // defeats the point of the scalar-only Meta view. listResultSummaries and
        // countResultsForScope call this once per saved result.
        Meta meta;
        try (java.io.Reader r = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            meta = GSON.fromJson(r, Meta.class);
        }
        if (meta == null) {
            throw new IOException("Result file is empty or corrupt: " + file);
        }
        return meta;
    }

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
    // Sidecar written next to each result by ClusteringRunRecord. It is a plain
    // ClusteringConfig (no cluster/cell data), so it must NOT be listed as a
    // result -- otherwise "View Past Results" shows a phantom "0 clusters" entry
    // beside the real one.
    private static final String CONFIG_SIDECAR_SUFFIX = "_config" + JSON_EXT;

    public static List<String> listResults(Project<?> project) throws IOException {
        Path resultsDir = getResultsDirectory(project);
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(resultsDir)) {
            files.filter(p -> p.toString().endsWith(JSON_EXT))
                    .filter(p -> !isConfigSidecar(p, resultsDir))
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
     * True if {@code p} is a ClusteringRunRecord config sidecar
     * ({@code <name>_config.json}) that sits beside its real result
     * ({@code <name>.json}). Requiring the sibling to exist avoids hiding a
     * result a user happened to name ending in "_config".
     */
    private static boolean isConfigSidecar(Path p, Path resultsDir) {
        String filename = p.getFileName().toString();
        if (!filename.endsWith(CONFIG_SIDECAR_SUFFIX)) return false;
        String base = filename.substring(0, filename.length() - CONFIG_SIDECAR_SUFFIX.length());
        return Files.exists(resultsDir.resolve(base + JSON_EXT));
    }

    /**
     * Load summary information for all saved results (for display in chooser).
     * Returns map of name -> summary string.
     */
    public static Map<String, String> listResultSummaries(Project<?> project) throws IOException {
        Map<String, String> summaries = new LinkedHashMap<>();
        for (String name : listResults(project)) {
            try {
                Meta meta = loadMeta(project, name);
                String summary = meta.timestamp != null
                        ? meta.timestamp.substring(0, Math.min(16, meta.timestamp.length()))
                        + " - " + meta.summary()
                        : meta.summary();
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
                    // Sanitize the FILENAME (keep the map key as-is): per-image spatial keys
                    // like "spatial_scatter::Image 1" contain ':' which is illegal in Windows
                    // filenames. The key still round-trips; only the on-disk name is cleaned.
                    String plotFilename = GeneralTools.stripInvalidFilenameChars(entry.getKey())
                            + getFileExtension(srcPath);
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
        writeJsonAtomic(file, saved);
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
        writeAtomic(file, w -> w.write(content));
    }

    /**
     * As {@link #writeStringAtomic} but serializing {@code value} straight into the
     * temp file's writer, so the JSON is never materialized as one giant String.
     * <p>
     * A saved result carries eight per-cell arrays (labels, embedding, image ids and
     * names, parent names, x, y, bbox). At a million cells {@code GSON.toJson(value)}
     * builds a ~250 MB String -- ~500 MB of {@code char[]} once UTF-16, with
     * StringBuilder doubling transients on top -- which is then re-encoded to a
     * separate UTF-8 byte array. Streaming removes both peaks. The on-disk format is
     * byte-for-byte identical to the String form.
     */
    private static void writeJsonAtomic(Path file, Object value) throws IOException {
        writeAtomic(file, w -> GSON.toJson(value, w));
    }

    /** Shared atomic temp-file-then-move body for the two writers above. */
    private static void writeAtomic(Path file, JsonBody body) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (java.io.Writer w = Files.newBufferedWriter(tmp, java.nio.charset.StandardCharsets.UTF_8)) {
            body.writeTo(w);
        }
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

    /** Writes the payload into an already-open writer. */
    @FunctionalInterface
    private interface JsonBody {
        void writeTo(java.io.Writer w) throws IOException;
    }

    /**
     * Parse a full saved result straight from the file. Streaming rather than
     * {@code Files.readString} matters here for the same reason it does in
     * {@link #loadMeta}: a million-cell result is hundreds of MB of JSON, and
     * reading it into a String first doubles the peak for no benefit.
     */
    private static SavedClusteringResult readSavedJson(Path file) throws IOException {
        try (java.io.Reader r = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, SavedClusteringResult.class);
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
     * Write a NON-DESTRUCTIVE renamed/merged COPY of an existing saved result. The
     * original {@code sourceName} JSON and its plots are left completely untouched;
     * a new result named {@code newName} is written carrying the custom
     * label-&gt;name map (a merge maps two+ labels to one name), a palette recolored
     * to the new names, and a self-contained copy of the source's plot images.
     * Backs the "Manage Clusters" rename/merge when a saved result is the target.
     *
     * @param nameByLabel cluster label -&gt; new display name (labels absent keep "Cluster N")
     * @param derivedOp   what the edit did ("rename", "merge", "split", or a
     *                    slash-joined combination); recorded as this copy's lineage
     * @return the sanitized base name actually used on disk
     * @throws IOException if the name is empty/duplicate or the source cannot be read
     */
    public static String saveRenamedCopy(Project<?> project, String sourceName, String newName,
                                         Map<Integer, String> nameByLabel, String derivedOp)
            throws IOException {
        if (newName == null || newName.trim().isEmpty()) {
            throw new IOException("New result name cannot be empty");
        }
        SavedClusteringResult saved = loadSavedResult(project, sourceName);  // fresh load; never mutated on disk
        Path resultsDir = getResultsDirectory(project);
        String safeName = GeneralTools.stripInvalidFilenameChars(newName.trim());
        if (safeName.isEmpty()) safeName = "result";
        if (safeName.equals(sourceName)) {
            throw new IOException("The copy name must differ from the original ('" + sourceName + "').");
        }
        Path targetJson = resultsDir.resolve(safeName + JSON_EXT);
        if (Files.exists(targetJson)) {
            throw new IOException("A saved result named '" + safeName + "' already exists.");
        }

        saved.setName(newName);
        saved.setTimestamp(LocalDateTime.now().toString());
        saved.setAutoSaved(false);
        saved.setClusterNames(nameByLabel);
        saved.setClusterColors(SavedResultApplier.renamedColors(saved, nameByLabel));
        // Provenance: what this copy came from, so an iterative chain of edits
        // stays legible and there is always a named result to step back to.
        saved.setDerivedFrom(sourceName);
        // What was actually done, not a fixed label: a split is the inverse of a
        // merge, and a chain that records both as "rename/merge" cannot be read back.
        saved.setDerivedOp(derivedOp != null && !derivedOp.isBlank() ? derivedOp : "edit");

        // Copy the plot images so the new result is self-contained, rewriting each
        // relative path's "<source>_plots/" prefix to "<copy>_plots/". (The PNG
        // legends still show the original cluster numbers; regenerate plots to
        // refresh them -- out of scope for a pure rename.)
        Map<String, String> plots = saved.getPlotPaths();
        if (plots != null && !plots.isEmpty()) {
            Path srcPlots = resultsDir.resolve(sourceName + PLOTS_SUFFIX);
            Path dstPlots = resultsDir.resolve(safeName + PLOTS_SUFFIX);
            String srcPrefix = sourceName + PLOTS_SUFFIX + "/";
            String dstPrefix = safeName + PLOTS_SUFFIX + "/";
            Map<String, String> rewritten = new LinkedHashMap<>();
            if (Files.isDirectory(srcPlots)) {
                if (!Files.exists(dstPlots)) Files.createDirectories(dstPlots);
                for (Map.Entry<String, String> e : plots.entrySet()) {
                    String rel = e.getValue();
                    if (rel == null || !rel.startsWith(srcPrefix)) continue;
                    String fname = rel.substring(srcPrefix.length());
                    Path s = srcPlots.resolve(fname);
                    if (Files.exists(s)) {
                        Files.copy(s, dstPlots.resolve(fname), StandardCopyOption.REPLACE_EXISTING);
                        rewritten.put(e.getKey(), dstPrefix + fname);
                    }
                }
            }
            saved.setPlotPaths(rewritten.isEmpty() ? null : rewritten);
        }

        writeJsonAtomic(targetJson, saved);
        logger.info("Wrote renamed copy '{}' of saved result '{}' ({} custom names)",
                safeName, sourceName, nameByLabel != null ? nameByLabel.size() : 0);
        return safeName;
    }

    /**
     * Write a labels-only saved result snapshotting the current classifications of
     * a set of cells (no embedding / markers / plots). Backs the optional "Save as
     * new result" on the "Manage Clusters" MANUAL path, which exists only when no
     * saved result was available to target -- so a first re-appliable result can be
     * bootstrapped from hand-labelled detections.
     *
     * @param labels       per-cell integer label (parallel to the cell arrays); &lt; 0 = unclassified
     * @param names        label -&gt; class name (so the snapshot re-applies the exact names)
     * @param cellImageIds per-cell source image id
     * @param cx,cy        per-cell centroid (full-res pixels)
     * @return the sanitized base name actually used on disk
     */
    public static String saveLabelSnapshot(Project<?> project, String name, int[] labels,
                                           Map<Integer, String> names, String[] cellImageIds,
                                           double[] cx, double[] cy,
                                           String scopeKey, String scopeLabel) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IOException("Result name cannot be empty");
        }
        Path resultsDir = getResultsDirectory(project);
        String safeName = GeneralTools.stripInvalidFilenameChars(name.trim());
        if (safeName.isEmpty()) safeName = "result";
        Path file = resultsDir.resolve(safeName + JSON_EXT);
        if (Files.exists(file)) {
            throw new IOException("A saved result named '" + safeName + "' already exists.");
        }

        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setName(name);
        saved.setTimestamp(LocalDateTime.now().toString());
        saved.setAlgorithm("manual");
        int nClusters = (int) Arrays.stream(labels).filter(l -> l >= 0).distinct().count();
        saved.setNClusters(nClusters);
        saved.setNCells(labels.length);
        saved.setClusterLabels(labels);
        saved.setClusterNames(names);
        saved.setCellImageIds(cellImageIds);
        saved.setCellX(cx);
        saved.setCellY(cy);
        saved.setClusterColors(SavedResultApplier.renamedColors(saved, names));
        saved.setScopeKey(scopeKey);
        saved.setScopeLabel(scopeLabel);
        saved.setAutoSaved(false);
        String extVer = GeneralTools.getPackageVersion(SavedClusteringResult.class);
        saved.setExtensionVersion(extVer != null ? extVer : "dev");
        saved.setQupathVersion(GeneralTools.getVersion().toString());

        writeJsonAtomic(file, saved);
        logger.info("Wrote manual label snapshot '{}' ({} cells, {} clusters)",
                safeName, labels.length, nClusters);
        return safeName;
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
                if (Objects.equals(scopeKey, loadMeta(project, name).scopeKey)) count++;
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
                e.derivedFrom = saved.getDerivedFrom();
                e.derivedOp = saved.getDerivedOp();
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
        /** Name of the result this one was derived from; null for an original run. */
        public String derivedFrom;
        /** What produced it ("rename/merge"); null for an original run. */
        public String derivedOp;
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
        SavedClusteringResult saved = readSavedJson(file);
        if (saved == null) {
            throw new IOException("Saved result '" + name + "' is empty or corrupt; "
                    + "cannot update its palette.");
        }
        saved.setClusterColors(colors);
        // Atomic: never truncate a good result file just to rewrite its palette.
        writeJsonAtomic(file, saved);
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

        SavedClusteringResult saved = readSavedJson(file);
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

        // Delete the ClusteringRunRecord sidecars (config JSON + run-info text)
        // so they do not orphan and reappear as phantom listings.
        Files.deleteIfExists(resultsDir.resolve(safeName + CONFIG_SIDECAR_SUFFIX));
        Files.deleteIfExists(resultsDir.resolve(safeName + "_RUN_INFO.txt"));

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
