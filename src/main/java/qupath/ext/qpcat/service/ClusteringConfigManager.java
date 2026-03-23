package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.lib.common.GeneralTools;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages saving and loading clustering configurations within a QuPath project.
 * Configs are stored as JSON files under {@code <project>/qpcat/cluster_configs/}.
 */
public class ClusteringConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringConfigManager.class);

    private static final String CONFIGS_DIR = "qpcat/cluster_configs";
    private static final String JSON_EXT = ".json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        Files.writeString(file, json);
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
