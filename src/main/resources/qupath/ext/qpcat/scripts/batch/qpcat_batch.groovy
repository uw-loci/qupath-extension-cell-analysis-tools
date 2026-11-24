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

import qupath.ext.qpcat.batch.BatchCliArgs
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
// An unknown --flag is refused (exit 2): a mistyped --dryrun must not run for real.
BatchCliArgs cli = BatchCliArgs.parse(args == null ? [] : (args as List<String>))
if (!cli.getErrors().isEmpty()) {
    for (String e : cli.getErrors()) {
        System.err.println("[qpcat-batch] " + e)
    }
    System.err.println("[qpcat-batch] usage: --args=<path-to-yaml-or-->")
    System.err.println("[qpcat-batch]   --dry-run                          validate without dispatching")
    System.err.println("[qpcat-batch]   --on-error=<continue|stop|retry:N>")
    System.err.println("[qpcat-batch]   --log-level=<DEBUG|INFO|WARN|ERROR>")
    System.err.println("[qpcat-batch]   --run-name=<name>")
    System.err.println("[qpcat-batch]   --workers=<N>")
    System.exit(2)
}
String yamlPath = cli.getYamlPath()
boolean stdin = cli.isStdin()
Map<String, String> overrides = cli.getOverrides()
boolean dryRun = cli.isDryRun()
boolean debug = "DEBUG".equalsIgnoreCase(overrides.get("log-level"))

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
if (cli.getWorkers() != null) {
    schema.setWorkers(cli.getWorkers())
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
