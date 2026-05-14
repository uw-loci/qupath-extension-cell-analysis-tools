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
 * <p>Image resolution: {@code "all"}, list of names, {@code {glob: ...}},
 * or {@code {regex: ...}}. Mixed shapes are caught earlier by
 * {@link BatchYamlValidator}; here we surface E008 (image not found)
 * and W003 (zero-match scope filter).</p>
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
        private final List<ValidationIssue> issues = new ArrayList<>();

        public List<ResolvedProject> getProjects() { return projects; }
        public List<ValidationIssue> getIssues() { return issues; }

        public boolean hasErrors() {
            for (ValidationIssue i : issues) {
                if (i.getSeverity() == ValidationIssue.Severity.ERROR) return true;
            }
            return false;
        }

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
            List<ValidationIssue> issues) {
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
                filterImages(project, scope, index, issues);
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

    @SuppressWarnings("unchecked")
    private static List<ProjectImageEntry<BufferedImage>> filterImages(
            Project<BufferedImage> project, BatchYamlSchema.ScopeBlock scope,
            int projectIndex, List<ValidationIssue> issues) {
        List<ProjectImageEntry<BufferedImage>> all = project.getImageList();
        Object filter = scope.getImages();
        if (filter == null || (filter instanceof String s && s.equalsIgnoreCase("all"))) {
            return new ArrayList<>(all);
        }
        // List of names
        if (filter instanceof List<?> rawList) {
            List<String> wanted = new ArrayList<>();
            for (Object o : rawList) if (o != null) wanted.add(o.toString());
            Map<String, ProjectImageEntry<BufferedImage>> byName = new LinkedHashMap<>();
            for (ProjectImageEntry<BufferedImage> e : all) {
                byName.put(e.getImageName(), e);
            }
            List<ProjectImageEntry<BufferedImage>> matched = new ArrayList<>();
            for (int i = 0; i < wanted.size(); i++) {
                String name = wanted.get(i);
                ProjectImageEntry<BufferedImage> e = byName.get(name);
                if (e == null) {
                    if (!scope.isSkipMissing()) {
                        issues.add(ValidationIssue.error("E008",
                                "scope.images[" + i + "]",
                                "image '" + BatchYamlParser.asciiSafe(name)
                                        + "' not found in project (set skip_missing: true to ignore)"));
                    } else {
                        issues.add(ValidationIssue.warning("W003",
                                "scope.images[" + i + "]",
                                "image '" + BatchYamlParser.asciiSafe(name)
                                        + "' not in project; skipped"));
                    }
                } else {
                    matched.add(e);
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
