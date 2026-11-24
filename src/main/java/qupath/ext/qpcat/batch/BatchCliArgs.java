package qupath.ext.qpcat.batch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line arguments of {@code qpcat_batch.groovy}.
 *
 * <p>An unknown {@code --flag} is an error, not ignored: a mistyped
 * {@code --dryrun} used to run the batch for real.</p>
 */
public final class BatchCliArgs {

    /** Flags that take no value. */
    private static final Set<String> SWITCHES = Set.of("dry-run", "dryRun");
    /** Flags that take a value ({@code --name=value}). */
    private static final Set<String> VALUED = Set.of("on-error", "log-level", "run-name", "workers");

    private String yamlPath;
    private boolean stdin;
    private boolean dryRun;
    private Integer workers;
    private final Map<String, String> overrides = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();

    private BatchCliArgs() {}

    /**
     * Parse the script's arguments.
     *
     * @param args the {@code --args} values in order; null is treated as empty
     * @return the parsed arguments; check {@link #getErrors()} before use
     */
    public static BatchCliArgs parse(List<String> args) {
        BatchCliArgs out = new BatchCliArgs();
        if (args == null) args = List.of();
        for (String a : args) {
            if (a == null) continue;
            if (a.equals("-")) {
                out.setPath("-", a);
                out.stdin = true;
            } else if (a.startsWith("--")) {
                out.parseFlag(a);
            } else {
                out.setPath(a, a);
            }
        }
        if (out.yamlPath == null && out.errors.isEmpty()) {
            out.errors.add("no YAML file given");
        }
        return out;
    }

    private void setPath(String path, String raw) {
        if (yamlPath != null) {
            errors.add("unexpected second positional argument: " + BatchYamlParser.asciiSafe(raw));
        } else {
            yamlPath = path;
        }
    }

    private void parseFlag(String a) {
        String stripped = a.substring(2);
        int eq = stripped.indexOf('=');
        String name = eq < 0 ? stripped : stripped.substring(0, eq);
        String value = eq < 0 ? null : stripped.substring(eq + 1);
        if (SWITCHES.contains(name)) {
            if (value != null) {
                errors.add("--" + name + " takes no value");
            } else {
                dryRun = true;
            }
            return;
        }
        if (!VALUED.contains(name)) {
            errors.add("unknown option '" + BatchYamlParser.asciiSafe(a) + "'. Known options: "
                    + "--dry-run, --on-error=<mode>, --log-level=<level>, --run-name=<name>, "
                    + "--workers=<N>");
            return;
        }
        if (value == null || value.isBlank()) {
            errors.add("--" + name + " needs a value: --" + name + "=<value>");
            return;
        }
        if (name.equals("workers")) {
            try {
                workers = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                errors.add("--workers must be a whole number, got '"
                        + BatchYamlParser.asciiSafe(value) + "'");
                return;
            }
        }
        overrides.put(name, value);
    }

    /** @return the YAML path, {@code "-"} for stdin, or null when none was given */
    public String getYamlPath() { return yamlPath; }

    /** @return true when the YAML is read from stdin */
    public boolean isStdin() { return stdin; }

    /** @return true for {@code --dry-run} */
    public boolean isDryRun() { return dryRun; }

    /** @return the {@code --workers} value, or null when not given */
    public Integer getWorkers() { return workers; }

    /** @return the valued options given, by name without the leading dashes */
    public Map<String, String> getOverrides() { return Collections.unmodifiableMap(overrides); }

    /** @return problems with the arguments; empty when they can be used */
    public List<String> getErrors() { return Collections.unmodifiableList(errors); }
}
