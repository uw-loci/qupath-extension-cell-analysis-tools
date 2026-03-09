package qupath.ext.pyclustering.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.pyclustering.model.ClusteringResult;
import qupath.ext.pyclustering.model.SavedClusteringResult;
import qupath.lib.common.GeneralTools;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages saving and loading clustering results within a QuPath project.
 * Results are stored as JSON under {@code <project>/pyclustering/cluster_results/}.
 * Plot images are copied into a subdirectory alongside the JSON.
 */
public class ClusteringResultManager {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringResultManager.class);

    private static final String RESULTS_DIR = "pyclustering/cluster_results";
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
                    .sorted(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime((Path) p); }
                        catch (IOException e) { return null; }
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
     * Save a clustering result to the project.
     * Copies plot images into the project directory so they persist.
     */
    public static void saveResult(Project<?> project, String name,
                                   ClusteringResult result,
                                   String algorithm, String normalization,
                                   String embeddingMethod) throws IOException {
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

        // Write JSON
        Path file = resultsDir.resolve(safeName + JSON_EXT);
        String json = GSON.toJson(saved);
        Files.writeString(file, json);
        logger.info("Saved clustering result '{}' to {} ({} clusters, {} cells)",
                name, file, result.getNClusters(), result.getNCells());
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

        return saved.toClusteringResult();
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
