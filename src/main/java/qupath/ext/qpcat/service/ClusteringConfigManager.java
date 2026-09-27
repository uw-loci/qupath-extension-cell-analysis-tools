package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.ToNumberPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.lib.common.GeneralTools;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages saving and loading clustering configurations within a QuPath project.
 * Configs are stored as JSON files under {@code <project>/qpcat/cluster_configs/}.
 */
public class ClusteringConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringConfigManager.class);

    private static final String CONFIGS_DIR = QpcatPaths.CLUSTER_CONFIGS;
    private static final String JSON_EXT = ".json";

    // The config's algorithm/embedding params are Map<String,Object>; without a
    // number strategy Gson deserializes every JSON number as Double, so a reloaded
    // config turns n_neighbors=50 into 50.0 -- which then reaches the Python side
    // (or an Integer cast) as a float. LONG_OR_DOUBLE keeps integral values as Long.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    private ClusteringConfigManager() {}

    /**
     * Get the configs directory for a project, creating it if needed.
     */
    public static Path getConfigsDirectory(Project<?> project) throws IOException {
        Path projectDir = project.getPath().getParent();
        Path configsDir = projectDir.resolve(CONFIGS_DIR);
        if (!Files.exists(configsDir)) {
            Files.createDirectories(configsDir);
            logger.info("Created clustering configs directory: {}", configsDir);
        }
        return configsDir;
    }

    /**
     * Store what the clustering dialog was just run with, so it reopens on those
     * choices rather than the hard-coded defaults.
     *
     * <p>A file, not a preference. The bulk of a config is the measurement
     * selection -- 262 names in one reported case -- and {@code java.util.prefs}
     * refuses any value over 8192 characters (on Windows it is the registry).
     * It refuses from a property listener, so the caller's own try/catch never
     * sees it: the run logged an IllegalArgumentException with the whole config
     * in the message and silently remembered nothing.
     *
     * <p>Per project, because measurement names and image names mean nothing in
     * a different one. Failure is never fatal -- not remembering a setting must
     * not stop a run -- so problems are logged and swallowed.
     *
     * @param project the open project; nothing is stored when null
     * @param modeKey which dialog mode these settings belong to
     * @param config  the config about to run
     */
    public static void saveLastRun(Project<?> project, String modeKey, ClusteringConfig config) {
        if (project == null || config == null || modeKey == null) {
            return;
        }
        try {
            Path dir = project.getPath().getParent().resolve(QpcatPaths.LAST_RUN);
            Files.createDirectories(dir);
            Path file = dir.resolve(modeKey + JSON_EXT);
            Path tmp = dir.resolve(modeKey + JSON_EXT + ".tmp");
            // Write then move: an interrupted write would otherwise leave a
            // truncated file that reads as a corrupt config on next open.
            Files.writeString(tmp, GSON.toJson(config));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not store the last run's settings: {}", e.getMessage());
        }
    }

    /**
     * The settings the clustering dialog last ran with in this mode.
     *
     * @param project the open project
     * @param modeKey which dialog mode to read
     * @return the stored config, or null when there is none or it cannot be read
     */
    public static ClusteringConfig loadLastRun(Project<?> project, String modeKey) {
        if (project == null || modeKey == null) {
            return null;
        }
        try {
            Path file = project.getPath().getParent()
                    .resolve(QpcatPaths.LAST_RUN).resolve(modeKey + JSON_EXT);
            if (!Files.exists(file)) {
                return null;
            }
            return GSON.fromJson(Files.readString(file), ClusteringConfig.class);
        } catch (IOException | RuntimeException e) {
            // A stale or hand-edited file must never stop the dialog opening.
            logger.debug("Could not restore the last run's settings: {}", e.getMessage());
            return null;
        }
    }

    /**
     * List available config names (without file extension).
     */
    public static List<String> listConfigs(Project<?> project) throws IOException {
        Path configsDir = getConfigsDirectory(project);
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(configsDir)) {
            files.filter(p -> p.toString().endsWith(JSON_EXT))
                    .sorted()
                    .forEach(p -> {
                        String filename = p.getFileName().toString();
                        names.add(filename.substring(0, filename.length() - JSON_EXT.length()));
                    });
        }
        return names;
    }

    /**
     * Save a clustering config to the project with the given name.
     */
    public static void saveConfig(Project<?> project, String name,
                                  ClusteringConfig config) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IOException("Config name cannot be empty");
        }

        Path configsDir = getConfigsDirectory(project);
        String safeName = GeneralTools.stripInvalidFilenameChars(name.trim());
        if (safeName.isEmpty()) {
            safeName = "config";
        }
        Path file = configsDir.resolve(safeName + JSON_EXT);

        String json = GSON.toJson(config);
        // Atomic write: a mid-write failure must not truncate an existing config.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("Saved clustering config '{}' to {}", name, file);
    }

    /**
     * Load a clustering config by name from the project.
     */
    public static ClusteringConfig loadConfig(Project<?> project, String name) throws IOException {
        Path configsDir = getConfigsDirectory(project);
        Path file = configsDir.resolve(name + JSON_EXT);

        if (!Files.exists(file)) {
            throw new IOException("Config file not found: " + file);
        }

        String json = Files.readString(file);
        ClusteringConfig config = GSON.fromJson(json, ClusteringConfig.class);
        logger.info("Loaded clustering config '{}' from {}", name, file);
        return config;
    }

    /**
     * Load a clustering config from an arbitrary JSON file (e.g. the
     * {@code <name>_config.json} written next to an auto-saved result), rather
     * than from the project's configs directory. Lets a run be reproduced from
     * its result folder without first importing the config into the project.
     */
    public static ClusteringConfig loadConfigFromFile(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            throw new IOException("Config file not found: " + file);
        }
        String json = Files.readString(file);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + " is not valid JSON");
        }
        // An empty file, a bare "null" or a top-level array all land here as "not
        // an object", which is the same answer to the user.
        JsonObject obj = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        String rejection = describeIfNotAConfig(file.getFileName().toString(), obj);
        if (rejection != null) {
            throw new IOException(rejection);
        }
        ClusteringConfig config = GSON.fromJson(json, ClusteringConfig.class);
        if (config == null) {
            throw new IOException("Config file did not contain a valid clustering config: " + file);
        }
        logger.info("Loaded clustering config from file {}", file);
        return config;
    }

    // Keys that appear only in a SAVED RESULT, never in a ClusteringConfig.
    private static final String[] RESULT_ONLY_KEYS = {
            "clusterLabels", "clusterStats", "markerNames", "nClusters", "nCells"
    };

    // Keys that a ClusteringConfig always writes. Gson fills a config from ANY
    // object, so without this check picking the wrong file silently loads a
    // default config instead of failing.
    private static final String[] CONFIG_KEYS = {
            "algorithm", "selectedMeasurements", "algorithmParams", "embeddingMethod"
    };

    /**
     * Why {@code obj} is not a clustering config, or null when it is one.
     * <p>
     * A project's {@code cluster_results/} folder holds two JSON files per run --
     * {@code <name>.json} (the result) and {@code <name>_config.json} (the config) --
     * and only the second is loadable here.
     *
     * @param filename name shown back to the user; the message has to name the file
     *                 they picked, because the two differ only by a suffix
     * @param obj      the parsed JSON, or null for an empty file / JSON {@code null}
     * @return a message naming what the file is and which one to pick instead, or null
     */
    static String describeIfNotAConfig(String filename, JsonObject obj) {
        if (obj == null) {
            return filename + " is empty, or does not contain a JSON object";
        }
        for (String key : RESULT_ONLY_KEYS) {
            if (obj.has(key)) {
                String base = filename.endsWith(JSON_EXT)
                        ? filename.substring(0, filename.length() - JSON_EXT.length())
                        : filename;
                return filename + " is a saved clustering RESULT, not a config. Pick '"
                        + base + "_config" + JSON_EXT + "' instead -- it is saved beside the "
                        + "result and holds the settings that produced it. To open the result "
                        + "itself, use View Past Results.";
            }
        }
        for (String key : CONFIG_KEYS) {
            if (obj.has(key)) {
                return null;
            }
        }
        return filename + " is not a QP-CAT clustering config (no 'algorithm' or "
                + "'selectedMeasurements' entry). Pick a '_config" + JSON_EXT + "' file "
                + "saved beside a result, or a config saved with Save Config...";
    }

    /**
     * Delete a config by name from the project.
     */
    public static void deleteConfig(Project<?> project, String name) throws IOException {
        Path configsDir = getConfigsDirectory(project);
        Path file = configsDir.resolve(name + JSON_EXT);

        if (Files.exists(file)) {
            Files.delete(file);
            logger.info("Deleted clustering config '{}'", name);
        }
    }

}
