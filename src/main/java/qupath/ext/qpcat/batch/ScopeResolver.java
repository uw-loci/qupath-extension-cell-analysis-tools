package qupath.ext.qpcat.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Resolves {@link BatchYamlSchema.ScopeBlock} entries to concrete
 * {@code Project} + {@code ProjectImageEntry} lists.
 *
 * <p>Project resolution: absolute path to {@code project.qpproj}, or
 * absolute path to its containing directory, are accepted in v1. Glob
 * / bare-name resolution (E013/E014) is deferred -- relative paths are
 * resolved against the JVM's working directory.</p>
 *
 * <p>Image resolution: {@code "all"}, one name, a list of names,
 * {@code {glob: ...}} or {@code {regex: ...}}. Mixed shapes are caught earlier
 * by {@link BatchYamlValidator}; here we surface E008 (image not found), W003
 * (zero-match scope filter) and E024 (two entries in scope share a name).</p>
 */
public final class ScopeResolver {

    private static final Logger logger = LoggerFactory.getLogger(ScopeResolver.class);

    private ScopeResolver() {}

    /** A resolved project + the images it contributed to the batch scope. */
    public static final class ResolvedProject {
        private final Path projectPath;
        private final Project<BufferedImage> project;
        private final List<ProjectImageEntry<BufferedImage>> images;

        public ResolvedProject(Path projectPath,
                                Project<BufferedImage> project,
                                List<ProjectImageEntry<BufferedImage>> images) {
            this.projectPath = projectPath;
            this.project = project;
            this.images = images;
        }

        public Path getProjectPath() { return projectPath; }
        public Project<BufferedImage> getProject() { return project; }
        public List<ProjectImageEntry<BufferedImage>> getImages() { return images; }
    }

    /** Outcome of resolving {@link BatchYamlSchema.ScopeBlock}. */
    public static final class ResolvedScope {
        private final List<ResolvedProject> projects = new ArrayList<>();
        private final ValidationResult issues = new ValidationResult();

        public List<ResolvedProject> getProjects() { return projects; }
        public List<ValidationIssue> getIssues() { return issues.getIssues(); }

        public boolean hasErrors() { return issues.hasErrors(); }

        public int totalImages() {
            int n = 0;
            for (ResolvedProject p : projects) n += p.getImages().size();
            return n;
        }
    }

    /** Resolve a scope block to concrete projects + images. */
    public static ResolvedScope resolve(BatchYamlSchema.ScopeBlock scope) {
        ResolvedScope out = new ResolvedScope();
        if (scope == null) {
            out.issues.add(ValidationIssue.error("E001", "scope", "scope block is null"));
            return out;
        }
        for (int i = 0; i < scope.getProjects().size(); i++) {
            String raw = scope.getProjects().get(i);
            ResolvedProject rp = resolveOneProject(raw, i, scope, out.issues);
            if (rp != null) out.projects.add(rp);
        }
        return out;
    }

    private static ResolvedProject resolveOneProject(
            String raw, int index, BatchYamlSchema.ScopeBlock scope,
            ValidationResult issues) {
        Path candidate;
        try {
            candidate = Path.of(raw).toAbsolutePath();
        } catch (Exception e) {
            issues.add(ValidationIssue.error("E003",
                    "scope.projects[" + index + "]",
                    "invalid path syntax '" + raw + "'"));
            return null;
        }
        Path projectFile;
        if (Files.isDirectory(candidate)) {
            projectFile = candidate.resolve("project.qpproj");
        } else {
            projectFile = candidate;
        }
        if (!Files.exists(projectFile)) {
            issues.add(ValidationIssue.error("E014",
                    "scope.projects[" + index + "]",
                    "project file '" + projectFile + "' does not exist"));
            return null;
        }

        Project<BufferedImage> project;
        try {
            project = ProjectIO.loadProject(projectFile.toFile(), BufferedImage.class);
        } catch (IOException e) {
            issues.add(ValidationIssue.error("E014",
                    "scope.projects[" + index + "]",
                    "failed to open project '" + projectFile + "': "
                            + BatchYamlParser.asciiSafe(e.getMessage())));
            return null;
        }

        List<ProjectImageEntry<BufferedImage>> matched =
                filterImages(project.getImageList(), scope, issues);
        refuseDuplicateNames(matched, projectFile.toString(), issues);
        if (matched.isEmpty() && !scope.isSkipMissing()) {
            // W003 -- but only if filter is more specific than "all"
            if (!isAllFilter(scope.getImages())) {
                issues.add(ValidationIssue.warning("W003",
                        "scope.images",
                        "filter matched zero images for project '" + projectFile + "'"));
            }
        }
        return new ResolvedProject(projectFile, project, matched);
    }

    private static boolean isAllFilter(Object images) {
        if (images == null) return true;
        if (images instanceof String s && s.equalsIgnoreCase("all")) return true;
        return false;
    }

    /**
     * The entries of one project that {@code scope.images} selects.
     * A bare string other than {@code all} is one image name.
     *
     * @param all    the project's entries
     * @param scope  the scope block
     * @param issues receives E008 / W003 / E003
     * @return the selected entries, in project order for a pattern, in listed order for names
     */
    @SuppressWarnings("unchecked")
    static List<ProjectImageEntry<BufferedImage>> filterImages(
            List<ProjectImageEntry<BufferedImage>> all, BatchYamlSchema.ScopeBlock scope,
            ValidationResult issues) {
        Object filter = scope.getImages();
        if (isAllFilter(filter)) {
            return new ArrayList<>(all);
        }
        // One name, or a list of names. A bare string used to select every image.
        if (filter instanceof String || filter instanceof List<?>) {
            List<String> wanted = new ArrayList<>();
            if (filter instanceof String one) {
                wanted.add(one);
            } else {
                for (Object o : (List<?>) filter) if (o != null) wanted.add(o.toString());
            }
            Map<String, List<ProjectImageEntry<BufferedImage>>> byName = new LinkedHashMap<>();
            for (ProjectImageEntry<BufferedImage> e : all) {
                byName.computeIfAbsent(e.getImageName(), k -> new ArrayList<>()).add(e);
            }
            List<ProjectImageEntry<BufferedImage>> matched = new ArrayList<>();
            for (int i = 0; i < wanted.size(); i++) {
                String name = wanted.get(i);
                List<ProjectImageEntry<BufferedImage>> named = byName.get(name);
                String path = filter instanceof String ? "scope.images" : "scope.images[" + i + "]";
                if (named == null) {
                    if (!scope.isSkipMissing()) {
                        issues.add(ValidationIssue.error("E008", path,
                                "image '" + BatchYamlParser.asciiSafe(name)
                                        + "' not found in project (set skip_missing: true to ignore)"));
                    } else {
                        issues.add(ValidationIssue.warning("W003", path,
                                "image '" + BatchYamlParser.asciiSafe(name)
                                        + "' not in project; skipped"));
                    }
                } else {
                    // Every entry with the name; refuseDuplicateNames reports a repeat.
                    matched.addAll(named);
                }
            }
            return matched;
        }
        // Glob / regex object
        if (filter instanceof Map<?, ?> map) {
            Object glob = ((Map<String, Object>) map).get("glob");
            Object regex = ((Map<String, Object>) map).get("regex");
            String pattern = null;
            boolean isRegex = false;
            if (glob != null) {
                pattern = globToRegex(glob.toString());
            } else if (regex != null) {
                pattern = regex.toString();
                isRegex = true;
            }
            if (pattern == null) return new ArrayList<>(all);
            try {
                Pattern compiled = Pattern.compile(pattern);
                List<ProjectImageEntry<BufferedImage>> matched = new ArrayList<>();
                for (ProjectImageEntry<BufferedImage> e : all) {
                    if (compiled.matcher(e.getImageName()).matches()) {
                        matched.add(e);
                    }
                }
                return matched;
            } catch (PatternSyntaxException pse) {
                issues.add(ValidationIssue.error("E003",
                        "scope.images." + (isRegex ? "regex" : "glob"),
                        "invalid pattern '" + BatchYamlParser.asciiSafe(pattern)
                                + "': " + BatchYamlParser.asciiSafe(pse.getDescription())));
                return new ArrayList<>();
            }
        }
        return new ArrayList<>(all);
    }

    /**
     * E024 for every image name used by more than one entry in {@code entries}.
     * Batch result names, figure file names and the figure-export lookup are
     * keyed by image name, so two entries sharing one would overwrite or pick up
     * each other's results.
     *
     * @param entries      the entries a project contributes to the run
     * @param projectLabel the project, for the message
     * @param issues       receives the errors
     */
    static void refuseDuplicateNames(List<ProjectImageEntry<BufferedImage>> entries,
                                     String projectLabel, ValidationResult issues) {
        Map<String, List<String>> idsByName = new LinkedHashMap<>();
        for (ProjectImageEntry<BufferedImage> e : entries) {
            idsByName.computeIfAbsent(e.getImageName(), k -> new ArrayList<>()).add(e.getID());
        }
        for (Map.Entry<String, List<String>> e : idsByName.entrySet()) {
            if (e.getValue().size() < 2) continue;
            issues.add(ValidationIssue.error("E024", "scope.images",
                    e.getValue().size() + " images in project '"
                    + BatchYamlParser.asciiSafe(projectLabel) + "' are named '"
                    + BatchYamlParser.asciiSafe(e.getKey()) + "' (entry ids " + e.getValue()
                    + "). Batch results and figures are named after the image, so they would "
                    + "overwrite each other. Rename one in QuPath's Project tab, or leave it "
                    + "out of scope.images."));
        }
    }

    /**
     * Translate a shell-style glob to a Java regex. Supports
     * {@code * ? [...]}; everything else is treated as literal.
     */
    static String globToRegex(String glob) {
        if (glob == null) return ".*";
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        sb.append('^');
        boolean inCharClass = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (inCharClass) {
                if (c == ']') inCharClass = false;
                sb.append(c);
                continue;
            }
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '[' -> { sb.append('['); inCharClass = true; }
                case '.', '(', ')', '+', '|', '^', '$', '{', '}', '\\' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return sb.toString();
    }
}
