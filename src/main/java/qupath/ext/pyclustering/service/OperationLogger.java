package qupath.ext.pyclustering.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.Project;

import qupath.lib.common.GeneralTools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent operation audit trail logger for the PyClustering extension.
 * <p>
 * Writes human-readable per-day log files to each project's
 * {@code pyclustering/logs/} directory. Every clustering, phenotyping,
 * embedding, export, and configuration operation is recorded with full
 * parameters, timestamps, and result summaries for reproducibility.
 * <p>
 * Log files roll over automatically at midnight -- operations on a new day
 * are written to a new file even if the application has been running
 * continuously.
 * <p>
 * Thread-safe: all writes are synchronized on the singleton instance.
 */
public class OperationLogger {

    private static final Logger logger = LoggerFactory.getLogger(OperationLogger.class);

    private static final String LOGS_DIR = "pyclustering/logs";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static OperationLogger instance;

    private Project<?> project;

    private OperationLogger() {}

    public static synchronized OperationLogger getInstance() {
        if (instance == null) {
            instance = new OperationLogger();
        }
        return instance;
    }

    /**
     * Set the active project. Call this whenever the project changes.
     * Pass null when no project is open.
     */
    public synchronized void setProject(Project<?> project) {
        this.project = project;
    }

    /**
     * Log a completed operation with parameters and a result summary.
     *
     * @param operationType  short label (e.g. "CLUSTERING", "PHENOTYPING")
     * @param parameters     ordered map of parameter names to display values
     * @param resultSummary  one-line result description (may be null on failure)
     * @param durationMs     wall-clock duration in milliseconds (-1 if unknown)
     */
    public synchronized void logOperation(String operationType,
                                           Map<String, String> parameters,
                                           String resultSummary,
                                           long durationMs) {
        Path logFile = resolveLogFile();
        if (logFile == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(operationType).append(" === ")
                .append(LocalDateTime.now().format(TIMESTAMP_FMT)).append("\n");
        appendVersionInfo(sb);

        if (parameters != null) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append("\n");
            }
        }

        if (resultSummary != null) {
            sb.append("  Result: ").append(resultSummary).append("\n");
        }

        if (durationMs >= 0) {
            sb.append("  Duration: ").append(formatDuration(durationMs)).append("\n");
        }

        sb.append("\n");

        appendToFile(logFile, sb.toString());
    }

    /**
     * Convenience: log an operation that failed.
     */
    public synchronized void logFailure(String operationType,
                                         Map<String, String> parameters,
                                         String errorMessage,
                                         long durationMs) {
        Path logFile = resolveLogFile();
        if (logFile == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(operationType).append(" [FAILED] === ")
                .append(LocalDateTime.now().format(TIMESTAMP_FMT)).append("\n");
        appendVersionInfo(sb);

        if (parameters != null) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append("\n");
            }
        }

        sb.append("  Error: ").append(errorMessage).append("\n");

        if (durationMs >= 0) {
            sb.append("  Duration: ").append(formatDuration(durationMs)).append("\n");
        }

        sb.append("\n");

        appendToFile(logFile, sb.toString());
    }

    /**
     * Log a simple event (e.g. config save/load, environment setup).
     */
    public synchronized void logEvent(String eventType, String description) {
        Path logFile = resolveLogFile();
        if (logFile == null) return;

        String text = "--- " + eventType + " --- "
                + LocalDateTime.now().format(TIMESTAMP_FMT) + "\n"
                + "  " + description + "\n\n";

        appendToFile(logFile, text);
    }

    // ---- Convenience builders for common operations ----

    /**
     * Build a parameter map for clustering operations.
     */
    public static Map<String, String> clusteringParams(String algorithm,
                                                        Map<String, Object> algorithmParams,
                                                        String normalization,
                                                        String embedding,
                                                        int nMeasurements,
                                                        int nCells,
                                                        boolean spatialAnalysis,
                                                        boolean batchCorrection) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Algorithm", algorithm);
        if (algorithmParams != null && !algorithmParams.isEmpty()) {
            params.put("Algorithm params", algorithmParams.toString());
        }
        params.put("Normalization", normalization);
        params.put("Embedding", embedding);
        params.put("Measurements", nMeasurements + " markers");
        params.put("Input", nCells + " cells");
        if (spatialAnalysis) params.put("Spatial analysis", "enabled");
        if (batchCorrection) params.put("Batch correction", "enabled");
        return params;
    }

    /**
     * Build a parameter map for project-wide clustering.
     */
    public static Map<String, String> projectClusteringParams(String algorithm,
                                                               Map<String, Object> algorithmParams,
                                                               String normalization,
                                                               String embedding,
                                                               int nMeasurements,
                                                               int nCells,
                                                               int nImages,
                                                               boolean batchCorrection) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Algorithm", algorithm);
        if (algorithmParams != null && !algorithmParams.isEmpty()) {
            params.put("Algorithm params", algorithmParams.toString());
        }
        params.put("Normalization", normalization);
        params.put("Embedding", embedding);
        params.put("Measurements", nMeasurements + " markers");
        params.put("Input", nCells + " cells across " + nImages + " images");
        if (batchCorrection) params.put("Batch correction", "enabled");
        return params;
    }

    /**
     * Build a parameter map for phenotyping operations.
     */
    public static Map<String, String> phenotypingParams(String normalization,
                                                         int nMarkers,
                                                         int nRules,
                                                         int nCells,
                                                         List<String> markerNames) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Normalization", normalization);
        params.put("Markers", nMarkers + " selected");
        params.put("Rules", nRules + " phenotype rules");
        params.put("Input", nCells + " cells");
        if (markerNames != null && !markerNames.isEmpty()) {
            params.put("Marker list", String.join(", ", markerNames));
        }
        return params;
    }

    /**
     * Build a parameter map for embedding operations.
     */
    public static Map<String, String> embeddingParams(String method,
                                                       String normalization,
                                                       Map<String, Object> methodParams,
                                                       int nMeasurements,
                                                       int nCells) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Method", method);
        params.put("Normalization", normalization);
        if (methodParams != null && !methodParams.isEmpty()) {
            params.put("Parameters", methodParams.toString());
        }
        params.put("Measurements", nMeasurements + " markers");
        params.put("Input", nCells + " cells");
        return params;
    }

    /**
     * Build a parameter map for sub-clustering operations.
     */
    public static Map<String, String> subclusteringParams(String parentCluster,
                                                           String algorithm,
                                                           int nCells) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Parent cluster", parentCluster);
        params.put("Algorithm", algorithm);
        params.put("Input", nCells + " cells");
        return params;
    }

    /**
     * Build a parameter map for AnnData export.
     */
    public static Map<String, String> exportParams(String outputPath,
                                                    int nCells,
                                                    int nMarkers) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Output", outputPath);
        params.put("Cells", String.valueOf(nCells));
        params.put("Markers", String.valueOf(nMarkers));
        return params;
    }

    /**
     * Build a parameter map for threshold computation.
     */
    public static Map<String, String> thresholdParams(String normalization,
                                                       int nMarkers,
                                                       int nCells) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Normalization", normalization);
        params.put("Markers", String.valueOf(nMarkers));
        params.put("Cells", String.valueOf(nCells));
        return params;
    }

    // ---- Internal helpers ----

    /**
     * Appends extension and QuPath version info to a log entry.
     */
    private static void appendVersionInfo(StringBuilder sb) {
        String extVersion = GeneralTools.getPackageVersion(OperationLogger.class);
        sb.append("  PyClustering version: ")
                .append(extVersion != null ? extVersion : "dev").append("\n");
        sb.append("  QuPath version: ")
                .append(GeneralTools.getVersion()).append("\n");
    }

    /**
     * Resolves the log file path for today. Always checks the current date
     * so that a new file is created if the day has changed mid-session.
     * Returns null if no project is set or directory creation fails.
     */
    private Path resolveLogFile() {
        if (project == null) {
            return null;
        }

        try {
            Path projectDir = project.getPath().getParent();
            Path logsDir = projectDir.resolve(LOGS_DIR);
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }
            String filename = "pyclustering_" + LocalDate.now().format(DATE_FMT) + ".log";
            return logsDir.resolve(filename);
        } catch (Exception e) {
            logger.warn("Failed to resolve log directory: {}", e.getMessage());
            return null;
        }
    }

    private void appendToFile(Path logFile, String text) {
        try (BufferedWriter writer = Files.newBufferedWriter(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(text);
        } catch (IOException e) {
            logger.warn("Failed to write operation log: {}", e.getMessage());
        }
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60_000) {
            return String.format("%.1fs", ms / 1000.0);
        } else {
            long minutes = ms / 60_000;
            long seconds = (ms % 60_000) / 1000;
            return minutes + "m " + seconds + "s";
        }
    }
}
