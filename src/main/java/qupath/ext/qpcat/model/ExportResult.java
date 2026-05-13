package qupath.ext.qpcat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a batch figure export run. Gson-serialisable; no JavaFX
 * dependencies. Returned by
 * {@code BatchFigureExporter.exportProject(...)} so callers (the dialog,
 * Feature C's YAML batch, unit tests) can summarise what happened.
 */
public final class ExportResult {

    private int filesWritten;
    private List<String> failures = new ArrayList<>();
    private long totalBytes;
    private boolean cancelled;

    public ExportResult() {}

    public int getFilesWritten() { return filesWritten; }
    public void setFilesWritten(int filesWritten) { this.filesWritten = filesWritten; }
    public void incrementFilesWritten() { this.filesWritten++; }

    public List<String> getFailures() { return failures; }
    public void setFailures(List<String> failures) {
        this.failures = failures == null ? new ArrayList<>() : new ArrayList<>(failures);
    }
    public void addFailure(String message) {
        if (message != null && !message.isEmpty()) failures.add(message);
    }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
    public void addBytes(long bytes) { this.totalBytes += bytes; }

    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    /**
     * Short single-line summary. Useful for log lines and notifications.
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(filesWritten).append(" file").append(filesWritten == 1 ? "" : "s");
        sb.append(" written");
        if (totalBytes > 0) {
            double mb = totalBytes / (1024.0 * 1024.0);
            sb.append(" (").append(String.format("%.1f", mb)).append(" MB)");
        }
        if (!failures.isEmpty()) {
            sb.append("; ").append(failures.size())
                    .append(" failure").append(failures.size() == 1 ? "" : "s");
        }
        if (cancelled) sb.append("; cancelled");
        return sb.toString();
    }
}
