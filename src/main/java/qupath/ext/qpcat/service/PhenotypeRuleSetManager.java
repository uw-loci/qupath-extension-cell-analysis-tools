package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.PhenotypeRuleSet;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages saving and loading phenotype rule sets within a QuPath project.
 * Rule sets are stored as JSON files under {@code <project>/qpcat/phenotype_rules/}.
 */
public class PhenotypeRuleSetManager {

    private static final Logger logger = LoggerFactory.getLogger(PhenotypeRuleSetManager.class);

    private static final String RULES_DIR = QpcatPaths.PHENOTYPE_RULES;
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

    private static final String RULESET_FALLBACK = "ruleset";

    /**
     * The base name a save of {@code name} would write over, or null when free.
     *
     * @param project the project
     * @param name    the requested rule set name
     * @return the base name already on disk, or null
     * @throws IOException if the rules directory cannot be created
     */
    public static String takenRuleSetName(Project<?> project, String name) throws IOException {
        String stem = FilenameSanitizer.sanitize(name.trim(), RULESET_FALLBACK);
        return FilenameSanitizer.stemTaken(getRulesDirectory(project), stem, JSON_EXT)
                ? stem : null;
    }

    /**
     * The base name {@link FilenameSanitizer.IfExists#NEW_NAME} would save {@code name} under.
     *
     * @param project the project
     * @param name    the requested rule set name
     * @return the first free base name
     * @throws IOException if the rules directory cannot be created
     */
    public static String freeRuleSetName(Project<?> project, String name) throws IOException {
        return FilenameSanitizer.reserveStem(getRulesDirectory(project),
                FilenameSanitizer.sanitize(name.trim(), RULESET_FALLBACK),
                FilenameSanitizer.IfExists.NEW_NAME, "A saved rule set", JSON_EXT);
    }

    /**
     * Save a rule set to the project. Filename is derived from the rule set name.
     *
     * @param ifExists what to do when a rule set file of that name exists
     * @return the base name written on disk
     * @throws FilenameSanitizer.FileExistsException when refused because the name is taken
     * @throws IOException when the name is empty, a gate is not finite, or the write fails
     */
    public static String saveRuleSet(Project<?> project, PhenotypeRuleSet ruleSet,
                                     FilenameSanitizer.IfExists ifExists) throws IOException {
        if (ruleSet.getName() == null || ruleSet.getName().trim().isEmpty()) {
            throw new IOException("Rule set name cannot be empty");
        }
        JsonFiles.requireFinite(ruleSet, "The rule set");

        Path rulesDir = getRulesDirectory(project);
        String safeName = FilenameSanitizer.reserveStem(rulesDir,
                FilenameSanitizer.sanitize(ruleSet.getName().trim(), RULESET_FALLBACK),
                ifExists, "A saved rule set", JSON_EXT);
        Path file = rulesDir.resolve(safeName + JSON_EXT);

        ruleSet.touch();
        JsonFiles.writeAtomic(file, GSON.toJson(ruleSet));
        logger.info("Saved phenotype rule set '{}' to {}", ruleSet.getName(), file);
        return safeName;
    }

    /**
     * Load a rule set by name from the project. A typed name also finds a file
     * saved before 0.26.0 under its old spelling.
     *
     * @throws IOException when the file is missing, empty, {@code null} or not a rule set
     */
    public static PhenotypeRuleSet loadRuleSet(Project<?> project, String name) throws IOException {
        Path rulesDir = getRulesDirectory(project);
        Path file = rulesDir.resolve(name + JSON_EXT);
        if (!Files.exists(file)) {
            for (String s : FilenameSanitizer.spellingsOnDisk(name, RULESET_FALLBACK)) {
                Path candidate = rulesDir.resolve(s + JSON_EXT);
                if (rulesDir.equals(candidate.getParent()) && Files.exists(candidate)) {
                    file = candidate;
                    break;
                }
            }
        }
        PhenotypeRuleSet ruleSet = JsonFiles.readObject(file, GSON, PhenotypeRuleSet.class,
                "a phenotype rule set");
        logger.info("Loaded phenotype rule set '{}' from {}", ruleSet.getName(), file);
        return ruleSet;
    }

    /**
     * Delete a saved rule set by name from the project.
     *
     * @return true when a rule set of that name existed and was deleted
     */
    public static boolean deleteRuleSet(Project<?> project, String name) throws IOException {
        if (name == null || name.isBlank()) {
            return false;
        }
        Path rulesDir = getRulesDirectory(project);
        Path file = rulesDir.resolve(name + JSON_EXT).normalize();
        if (!rulesDir.normalize().equals(file.getParent())) {
            return false;
        }
        boolean deleted = Files.deleteIfExists(file);
        if (deleted) {
            logger.info("Deleted phenotype rule set '{}'", name);
        }
        return deleted;
    }
}
