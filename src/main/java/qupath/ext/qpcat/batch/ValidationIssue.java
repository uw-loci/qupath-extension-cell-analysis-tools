package qupath.ext.qpcat.batch;

/**
 * A single validation issue surfaced by {@link BatchYamlValidator}.
 *
 * <p>Each issue carries a stable error / warning code (E001-E021, W001-W004),
 * a YAML field path (e.g. {@code scope.projects[1]}), and a human-readable
 * message. Issues are immutable.</p>
 *
 * <p>ASCII-only message contents -- the runtime is cp1252 on Windows and
 * Unicode strings in log output have hung workflows in the past.</p>
 */
public final class ValidationIssue {

    /** Issue severity: error blocks the run, warning allows it. */
    public enum Severity { ERROR, WARNING }

    private final Severity severity;
    private final String code;       // e.g. "E001", "W002"
    private final String fieldPath;  // e.g. "scope.projects[1]"
    private final String message;

    public ValidationIssue(Severity severity, String code, String fieldPath, String message) {
        this.severity = severity;
        this.code = code;
        this.fieldPath = fieldPath;
        this.message = message;
    }

    public static ValidationIssue error(String code, String fieldPath, String message) {
        return new ValidationIssue(Severity.ERROR, code, fieldPath, message);
    }

    public static ValidationIssue warning(String code, String fieldPath, String message) {
        return new ValidationIssue(Severity.WARNING, code, fieldPath, message);
    }

    public Severity getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getFieldPath() { return fieldPath; }
    public String getMessage() { return message; }

    /**
     * Format the issue as a single ASCII line for stderr emit.
     * Example: {@code "E002 clustering.algorithm: unknown field. Did you mean 'type'?"}
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(code).append(' ').append(fieldPath).append(": ").append(message);
        return sb.toString();
    }

    @Override
    public String toString() {
        return severity + " " + format();
    }
}
