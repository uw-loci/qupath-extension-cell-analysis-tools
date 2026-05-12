package qupath.ext.qpcat.model;

import java.util.List;

/**
 * One LLM-generated explanation row for a single cluster.
 * <p>
 * Produced by the LLM Cluster Explainer feature (see
 * {@code LlmExplainerService}) and rendered as one row in the
 * "Cluster Explainer (LLM) [Beta]" tab of the cluster results dialog.
 * <p>
 * The LLM is allowed to refuse: {@link #phenotype} and {@link #confidence}
 * may both be {@code null} when the model considers the signal insufficient.
 * In that case the {@link #rationale} explains why.
 * <p>
 * This class is also persisted as part of {@link SavedClusteringResult} via
 * a single Gson field; older saves that pre-date the LLM feature load with
 * the explanations field as {@code null}.
 */
public class ClusterExplanation {

    /** Three confidence bands surfaced by the LLM. */
    public enum Confidence {
        HIGH, MEDIUM, LOW;

        /**
         * Map a raw LLM string ("high"/"medium"/"low" with any case) or
         * {@code null} to a {@link Confidence} or {@code null}. Returns
         * {@code null} for any unrecognised value (including the LLM's
         * "insufficient signal" path).
         */
        public static Confidence fromRaw(String raw) {
            if (raw == null) return null;
            String norm = raw.trim().toLowerCase();
            return switch (norm) {
                case "high" -> HIGH;
                case "medium", "med", "moderate" -> MEDIUM;
                case "low" -> LOW;
                default -> null;
            };
        }

        /** Short 2-letter label for the table cell. */
        public String shortLabel() {
            return switch (this) {
                case HIGH -> "HI";
                case MEDIUM -> "MD";
                case LOW -> "LO";
            };
        }
    }

    private int clusterId;
    private String phenotype;            // null if LLM refused (insufficient signal)
    private Confidence confidence;       // null if LLM refused
    private String rationale;            // never null when the LLM responded
    private List<String> supportingMarkers;

    public ClusterExplanation() {}

    public ClusterExplanation(int clusterId, String phenotype, Confidence confidence,
                              String rationale, List<String> supportingMarkers) {
        this.clusterId = clusterId;
        this.phenotype = phenotype;
        this.confidence = confidence;
        this.rationale = rationale;
        this.supportingMarkers = supportingMarkers;
    }

    public int getClusterId() { return clusterId; }
    public void setClusterId(int clusterId) { this.clusterId = clusterId; }

    public String getPhenotype() { return phenotype; }
    public void setPhenotype(String phenotype) { this.phenotype = phenotype; }

    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence confidence) { this.confidence = confidence; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public List<String> getSupportingMarkers() { return supportingMarkers; }
    public void setSupportingMarkers(List<String> supportingMarkers) {
        this.supportingMarkers = supportingMarkers;
    }

    /**
     * Display-formatted phenotype for the table cell. Returns the literal
     * "(no suggestion)" string for rows where the LLM declined to suggest.
     */
    public String displayPhenotype() {
        return phenotype != null ? phenotype : "(no suggestion)";
    }

    /**
     * Short 2-letter label or empty string when the LLM declined.
     */
    public String displayConfidence() {
        return confidence != null ? confidence.shortLabel() : "";
    }

    /**
     * Comma-joined supporting marker list for the table cell.
     */
    public String displaySupportingMarkers() {
        if (supportingMarkers == null || supportingMarkers.isEmpty()) return "";
        return String.join(", ", supportingMarkers);
    }
}
