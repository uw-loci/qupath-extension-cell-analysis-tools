// QP-CAT YAML Headless Batch -- entry script.
//
// Invoke:
//   QuPath script qpcat_batch.groovy --args=<path-to-yaml> [--args=--dry-run] ...
//
// CLI overrides supported in v1:
//   --dry-run               Validate + describe, no dispatch.
//   --on-error=<mode>       Override schema's on_error (continue|stop|retry:N).
//   --log-level=<level>     DEBUG|INFO|WARN|ERROR.
//   --run-name=<name>       Override audit run identifier.
//   --workers=<N>           Override workers; v1 still coerces to 1 (W002).
//
// Exit codes per design Section 6:
//   0  success
//   1  partial failure (continue mode)
//   2  fatal configuration / validation error
//   3  fatal runtime error (stop mode or unrecoverable Appose failure)
//
// ASCII-only output by policy. Cite OpenIMC for the schema shape; the
// QuPath script-mode launcher hosts the runtime.

import qupath.ext.qpcat.batch.BatchYamlParser
import qupath.ext.qpcat.batch.BatchYamlValidator
import qupath.ext.qpcat.batch.StdoutProgressEmitter
import qupath.ext.qpcat.batch.ValidationIssue
import qupath.ext.qpcat.batch.YamlBatchOrchestrator
import qupath.ext.qpcat.batch.ProgressEmitter

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// Parse arguments. The Groovy 'args' binding is the picocli --args list.
def argList = args == null ? [] : (args as List)
String yamlPath = null
Map<String, String> overrides = [:]
boolean stdin = false

for (String a : argList) {
    if (a == null) continue
    if (yamlPath == null && !a.startsWith("--")) {
        yamlPath = a
    } else if (a == "-") {
        stdin = true
        yamlPath = "-"
    } else if (a.startsWith("--")) {
        def stripped = a.substring(2)
        def eq = stripped.indexOf("=")
        if (eq < 0) {
            overrides[stripped] = "true"
        } else {
            overrides[stripped.substring(0, eq)] = stripped.substring(eq + 1)
        }
    } else {
        System.err.println("[qpcat-batch] unexpected positional argument: " + a)
        System.exit(2)
    }
}

if (yamlPath == null) {
    System.err.println("[qpcat-batch] usage: --args=<path-to-yaml-or-->")
    System.err.println("[qpcat-batch]   --dry-run                          validate without dispatching")
    System.err.println("[qpcat-batch]   --on-error=<continue|stop|retry:N>")
    System.err.println("[qpcat-batch]   --log-level=<DEBUG|INFO|WARN|ERROR>")
    System.err.println("[qpcat-batch]   --run-name=<name>")
    System.exit(2)
}

boolean dryRun = overrides.containsKey("dry-run") || overrides.containsKey("dryRun")
boolean debug = ("DEBUG".equalsIgnoreCase(overrides.get("log-level"))
        || "DEBUG".equalsIgnoreCase(overrides.get("log_level")))

ProgressEmitter emitter = new StdoutProgressEmitter(System.out, System.err, debug)

// Parse YAML
BatchYamlParser.ParseOutcome parseOutcome
String yamlContent = null
if (stdin) {
    StringBuilder sb = new StringBuilder()
    BufferedReader br = new BufferedReader(new InputStreamReader(System.in,
            java.nio.charset.StandardCharsets.UTF_8))
    String line
    while ((line = br.readLine()) != null) {
        sb.append(line).append('\n')
    }
    yamlContent = sb.toString()
    parseOutcome = BatchYamlParser.parseString(yamlContent)
} else {
    Path yp = Paths.get(yamlPath)
    if (!Files.exists(yp)) {
        emitter.emit(ProgressEmitter.Level.ERROR, "YAML file not found: " + yamlPath)
        System.exit(2)
    }
    parseOutcome = BatchYamlParser.parse(yp)
}

// Apply CLI overrides
def schema = parseOutcome.getSchema()
if (overrides.containsKey("on-error")) {
    schema.setOnError(overrides.get("on-error"))
}
if (overrides.containsKey("run-name") && schema.getAudit() != null) {
    schema.getAudit().setRunName(overrides.get("run-name"))
}
if (overrides.containsKey("log-level") && schema.getAudit() != null) {
    schema.getAudit().setLogLevel(overrides.get("log-level"))
}
if (overrides.containsKey("workers")) {
    try {
        schema.setWorkers(Integer.parseInt(overrides.get("workers").trim()))
    } catch (NumberFormatException ignore) {}
}

// Emit parse issues
def parseIssues = parseOutcome.getIssues()
boolean hasParseErrors = false
if (parseIssues != null) {
    for (ValidationIssue issue : parseIssues) {
        def lvl = (issue.getSeverity() == ValidationIssue.Severity.ERROR)
                ? ProgressEmitter.Level.ERROR
                : ProgressEmitter.Level.WARN
        emitter.emit(lvl, issue.format())
        if (issue.getSeverity() == ValidationIssue.Severity.ERROR) hasParseErrors = true
    }
}

// Semantic validation
def validation = BatchYamlValidator.validate(schema)
for (ValidationIssue issue : validation.getIssues()) {
    def lvl = (issue.getSeverity() == ValidationIssue.Severity.ERROR)
            ? ProgressEmitter.Level.ERROR
            : ProgressEmitter.Level.WARN
    emitter.emit(lvl, issue.format())
}

if (hasParseErrors || validation.hasErrors()) {
    emitter.emit(ProgressEmitter.Level.ERROR,
            "Run aborted before any work dispatched.")
    emitter.emit(ProgressEmitter.Level.ERROR, "Exit code: 2")
    System.exit(2)
}

// Run
def runOpts = new YamlBatchOrchestrator.BatchRunOptions()
        .setDryRun(dryRun)
        .setYamlPath(stdin ? "-" : yamlPath)
if (yamlContent != null) runOpts.setYamlContent(yamlContent)

def outcome = YamlBatchOrchestrator.runBatch(schema, runOpts, emitter)
System.exit(outcome.getExitCode())
