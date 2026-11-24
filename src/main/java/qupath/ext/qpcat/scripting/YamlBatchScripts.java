package qupath.ext.qpcat.scripting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.batch.BatchYamlParser;
import qupath.ext.qpcat.batch.BatchYamlSchema;
import qupath.ext.qpcat.batch.BatchYamlValidator;
import qupath.ext.qpcat.batch.ProgressEmitter;
import qupath.ext.qpcat.batch.StdoutProgressEmitter;
import qupath.ext.qpcat.batch.ValidationIssue;
import qupath.ext.qpcat.batch.ValidationResult;
import qupath.ext.qpcat.batch.YamlBatchOrchestrator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Public, FX-free Groovy facade for QP-CAT's YAML headless-batch runner.
 *
 * <p>The recommended user-facing surface is the bundled
 * {@code qpcat_batch.groovy} script invoked via
 * {@code QuPath script qpcat_batch.groovy --args=config.yaml}. This
 * scripting facade is the layer underneath -- useful for users who want
 * to construct or transform the YAML config in Groovy before running.</p>
 *
 * <p><strong>Stability promise (v1).</strong> Package path
 * ({@code qupath.ext.qpcat.scripting}), class name ({@code YamlBatchScripts}),
 * method names ({@code runBatch}), and the recognized option-key set listed
 * below are part of QP-CAT's public scripting API. Breaking changes go
 * through a deprecation period of at least one minor version.</p>
 *
 * <p>Recognized option keys:</p>
 * <ul>
 *   <li>{@code config} -- {@link java.nio.file.Path} or {@link String}
 *       (path to YAML file) or {@link String} of inline YAML content: any
 *       text with a line break, or one line starting 'version:'.</li>
 *   <li>{@code dryRun} -- boolean, default false. Validate + describe
 *       without dispatching.</li>
 *   <li>{@code emitter} -- optional {@link ProgressEmitter}; default
 *       {@link StdoutProgressEmitter}.</li>
 * </ul>
 */
public final class YamlBatchScripts {

    private static final Logger logger = LoggerFactory.getLogger(YamlBatchScripts.class);

    private YamlBatchScripts() {}

    /** Run the batch from an options map. Returns the orchestrator outcome. */
    public static YamlBatchOrchestrator.BatchOutcome runBatch(Map<String, ?> opts)
            throws IOException {
        if (opts == null) {
            throw new IOException("YamlBatchScripts.runBatch: opts map is null");
        }
        Object config = opts.get("config");
        boolean dryRun = readBool(opts.get("dryRun"), false);
        ProgressEmitter emitter = (opts.get("emitter") instanceof ProgressEmitter pe)
                ? pe : new StdoutProgressEmitter();

        if (config == null) {
            throw new IOException("YamlBatchScripts.runBatch: 'config' is required");
        }

        BatchYamlOrchestratorInput input = parseInput(config);
        if (input.parseIssues != null) {
            for (ValidationIssue i : input.parseIssues) {
                emitter.emit(i.getSeverity() == ValidationIssue.Severity.ERROR
                                ? ProgressEmitter.Level.ERROR
                                : ProgressEmitter.Level.WARN,
                        i.format());
            }
        }

        // Semantic validation
        ValidationResult vr = BatchYamlValidator.validate(input.schema);
        for (ValidationIssue i : vr.getIssues()) {
            emitter.emit(i.getSeverity() == ValidationIssue.Severity.ERROR
                            ? ProgressEmitter.Level.ERROR
                            : ProgressEmitter.Level.WARN,
                    i.format());
        }

        boolean hasParseErrors = input.parseIssues != null
                && input.parseIssues.stream().anyMatch(
                        i -> i.getSeverity() == ValidationIssue.Severity.ERROR);
        if (hasParseErrors || vr.hasErrors()) {
            emitter.emit(ProgressEmitter.Level.ERROR,
                    "Run aborted before any work dispatched");
            YamlBatchOrchestrator.BatchOutcome outcome = new YamlBatchOrchestrator.BatchOutcome();
            outcome.setExitCode(2);
            return outcome;
        }

        YamlBatchOrchestrator.BatchRunOptions runOpts =
                new YamlBatchOrchestrator.BatchRunOptions()
                        .setDryRun(dryRun)
                        .setYamlPath(input.yamlPath);
        if (input.yamlContent != null) runOpts.setYamlContent(input.yamlContent);
        return YamlBatchOrchestrator.runBatch(input.schema, runOpts, emitter);
    }

    // ---------------- helpers ----------------

    private static final class BatchYamlOrchestratorInput {
        BatchYamlSchema schema;
        java.util.List<ValidationIssue> parseIssues;
        String yamlPath;
        String yamlContent;
    }

    /**
     * True when a string config is YAML text rather than a file path: any line
     * break (a path never holds one, and YAML that starts with a comment does
     * not start with {@code version:}), or a single line starting {@code version:}.
     */
    static boolean isInlineYaml(String s) {
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.trim().startsWith("version:");
    }

    private static BatchYamlOrchestratorInput parseInput(Object config) throws IOException {
        BatchYamlOrchestratorInput out = new BatchYamlOrchestratorInput();
        if (config instanceof Path p) {
            BatchYamlParser.ParseOutcome po = BatchYamlParser.parse(p);
            out.schema = po.getSchema();
            out.parseIssues = po.getIssues();
            out.yamlPath = p.toString();
            return out;
        }
        if (config instanceof String s) {
            if (isInlineYaml(s)) {
                BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(s);
                out.schema = po.getSchema();
                out.parseIssues = po.getIssues();
                out.yamlContent = s;
                return out;
            }
            // Otherwise treat as path
            Path path;
            try {
                path = Path.of(s.trim());
            } catch (java.nio.file.InvalidPathException e) {
                throw new IOException("The batch config is neither YAML text nor a file path: "
                        + e.getMessage());
            }
            BatchYamlParser.ParseOutcome po = BatchYamlParser.parse(path);
            out.schema = po.getSchema();
            out.parseIssues = po.getIssues();
            out.yamlPath = path.toString();
            return out;
        }
        if (config instanceof Map<?, ?>) {
            throw new IOException("An in-memory Map config is not supported; "
                    + "pass a YAML file path or YAML string content");
        }
        throw new IOException("Unsupported 'config' type: "
                + config.getClass().getSimpleName());
    }

    private static boolean readBool(Object raw, boolean dflt) {
        if (raw == null) return dflt;
        if (raw instanceof Boolean b) return b;
        String s = raw.toString().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> dflt;
        };
    }
}
