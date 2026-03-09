package qupath.ext.pyclustering.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.pyclustering.model.PhenotypeRuleSet;
import qupath.lib.common.GeneralTools;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages saving and loading phenotype rule sets within a QuPath project.
 * Rule sets are stored as JSON files under {@code <project>/pyclustering/phenotype_rules/}.
 */
public class PhenotypeRuleSetManager {

    private static final Logger logger = LoggerFactory.getLogger(PhenotypeRuleSetManager.class);

    private static final String RULES_DIR = "pyclustering/phenotype_rules";
    private static final String JSON_EXT = ".json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PhenotypeRuleSetManager() {}

    /**
     * Get the rules directory for a project, creating it if needed.
     */
    public static Path getRulesDirectory(Project<?> project) throws IOException {
        Path projectDir = project.getPath().getParent();
        Path rulesDir = projectDir.resolve(RULES_DIR);
        if (!Files.exists(rulesDir)) {
            Files.createDirectories(rulesDir);
            logger.info("Created phenotype rules directory: {}", rulesDir);
        }
        return rulesDir;
    }

    /**
     * List available rule set names (without file extension).
     */
    public static List<String> listRuleSets(Project<?> project) throws IOException {
        Path rulesDir = getRulesDirectory(project);
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(rulesDir)) {
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
     * Save a rule set to the project. Filename is derived from the rule set name.
     */
    public static void saveRuleSet(Project<?> project, PhenotypeRuleSet ruleSet) throws IOException {
        if (ruleSet.getName() == null || ruleSet.getName().trim().isEmpty()) {
            throw new IOException("Rule set name cannot be empty");
        }

        ruleSet.touch();

        Path rulesDir = getRulesDirectory(project);
        String safeName = GeneralTools.stripInvalidFilenameChars(ruleSet.getName().trim());
        if (safeName.isEmpty()) {
            safeName = "ruleset";
        }
        Path file = rulesDir.resolve(safeName + JSON_EXT);

        String json = GSON.toJson(ruleSet);
        Files.writeString(file, json);
        logger.info("Saved phenotype rule set '{}' to {}", ruleSet.getName(), file);
    }

    /**
     * Load a rule set by name from the project.
     */
    public static PhenotypeRuleSet loadRuleSet(Project<?> project, String name) throws IOException {
        Path rulesDir = getRulesDirectory(project);
        Path file = rulesDir.resolve(name + JSON_EXT);

        if (!Files.exists(file)) {
            throw new IOException("Rule set file not found: " + file);
        }

        String json = Files.readString(file);
        PhenotypeRuleSet ruleSet = GSON.fromJson(json, PhenotypeRuleSet.class);
        logger.info("Loaded phenotype rule set '{}' from {}", ruleSet.getName(), file);
        return ruleSet;
    }

    /**
     * Delete a rule set by name from the project.
     */
    public static void deleteRuleSet(Project<?> project, String name) throws IOException {
        Path rulesDir = getRulesDirectory(project);
        Path file = rulesDir.resolve(name + JSON_EXT);

        if (Files.exists(file)) {
            Files.delete(file);
            logger.info("Deleted phenotype rule set '{}'", name);
        }
    }
}
