package qupath.ext.qpcat.batch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated result of {@link BatchYamlValidator#validate(BatchYamlSchema)}.
 *
 * <p>Accumulates {@link ValidationIssue} entries; {@link #hasErrors()} is
 * the gate the orchestrator checks before dispatching any real work.</p>
 */
public final class ValidationResult {

    private final List<ValidationIssue> issues = new ArrayList<>();

    public ValidationResult() {}

    public void add(ValidationIssue issue) {
        if (issue != null) issues.add(issue);
    }

    public void addAll(List<ValidationIssue> incoming) {
        if (incoming != null) {
            for (ValidationIssue i : incoming) add(i);
        }
    }

    public List<ValidationIssue> getIssues() {
        return Collections.unmodifiableList(issues);
    }

    public List<ValidationIssue> getErrors() {
        List<ValidationIssue> out = new ArrayList<>();
        for (ValidationIssue i : issues) {
            if (i.getSeverity() == ValidationIssue.Severity.ERROR) out.add(i);
        }
        return out;
    }

    public List<ValidationIssue> getWarnings() {
        List<ValidationIssue> out = new ArrayList<>();
        for (ValidationIssue i : issues) {
            if (i.getSeverity() == ValidationIssue.Severity.WARNING) out.add(i);
        }
        return out;
    }

    public boolean hasErrors() {
        for (ValidationIssue i : issues) {
            if (i.getSeverity() == ValidationIssue.Severity.ERROR) return true;
        }
        return false;
    }

    public boolean hasWarnings() {
        for (ValidationIssue i : issues) {
            if (i.getSeverity() == ValidationIssue.Severity.WARNING) return true;
        }
        return false;
    }

    public int errorCount() { return getErrors().size(); }
    public int warningCount() { return getWarnings().size(); }

    /** True when there are zero errors AND zero warnings. */
    public boolean isClean() { return issues.isEmpty(); }

    @Override
    public String toString() {
        return "ValidationResult[errors=" + errorCount()
                + " warnings=" + warningCount() + "]";
    }
}
