package qupath.ext.qpcat.model;

import java.util.Map;

/**
 * Holds the results of a clustering run returned from Python.
 */
public class ClusteringResult {

    private final int[] clusterLabels;
    private final int nClusters;
    private final double[][] embedding;       // may be null if no embedding was computed
    private final double[][] clusterStats;    // per-cluster marker means (nClusters x nMarkers)
    private final String[] markerNames;

    // Post-analysis results (set after construction)
    private String markerRankingsJson;
    private double[][] pagaConnectivity;
    private String[] pagaClusterNames;
    private Map<String, String> plotPaths;

    public ClusteringResult(int[] clusterLabels, int nClusters, double[][] embedding,
                            double[][] clusterStats, String[] markerNames) {
        this.clusterLabels = clusterLabels;
        this.nClusters = nClusters;
        this.embedding = embedding;
        this.clusterStats = clusterStats;
        this.markerNames = markerNames;
    }

    public int[] getClusterLabels() { return clusterLabels; }
    public int getNClusters() { return nClusters; }
    public double[][] getEmbedding() { return embedding; }
    public double[][] getClusterStats() { return clusterStats; }
    public String[] getMarkerNames() { return markerNames; }
    public boolean hasEmbedding() { return embedding != null; }
    public int getNCells() { return clusterLabels.length; }

    public String getMarkerRankingsJson() { return markerRankingsJson; }
    public void setMarkerRankingsJson(String json) { this.markerRankingsJson = json; }
    public boolean hasMarkerRankings() { return markerRankingsJson != null; }

    public double[][] getPagaConnectivity() { return pagaConnectivity; }
    public void setPagaConnectivity(double[][] conn) { this.pagaConnectivity = conn; }
    public String[] getPagaClusterNames() { return pagaClusterNames; }
    public void setPagaClusterNames(String[] names) { this.pagaClusterNames = names; }
    public boolean hasPagaConnectivity() { return pagaConnectivity != null; }

    public Map<String, String> getPlotPaths() { return plotPaths; }
    public void setPlotPaths(Map<String, String> paths) { this.plotPaths = paths; }
    public boolean hasPlots() { return plotPaths != null && !plotPaths.isEmpty(); }

    // Spatial analysis results
    private double[][] nhoodEnrichment;
    private String[] nhoodClusterNames;
    private String spatialAutocorrJson;

    public double[][] getNhoodEnrichment() { return nhoodEnrichment; }
    public void setNhoodEnrichment(double[][] m) { this.nhoodEnrichment = m; }
    public String[] getNhoodClusterNames() { return nhoodClusterNames; }
    public void setNhoodClusterNames(String[] names) { this.nhoodClusterNames = names; }
    public boolean hasNhoodEnrichment() { return nhoodEnrichment != null; }

    public String getSpatialAutocorrJson() { return spatialAutocorrJson; }
    public void setSpatialAutocorrJson(String json) { this.spatialAutocorrJson = json; }
    public boolean hasSpatialAutocorr() { return spatialAutocorrJson != null; }

    // ==================== Spatial Stats Expansion (v1) ====================
    //
    // Optional results for the v1 spatial-statistics surface: Ripley K/L,
    // Geary's C, and co-occurrence (pairwise + one-vs-rest). Older callers
    // that never set these get null on each accessor and the dialog renders
    // exactly as it did pre-v1.

    private RipleyResult ripley;
    private GearyCResult geary;
    private CoOccurrenceResult coOccurrencePairwise;
    private CoOccurrenceResult coOccurrenceOneVsRest;
    private String spatialGraphType;       // "knn" | "radius" | "delaunay"

    public RipleyResult getRipley() { return ripley; }
    public void setRipley(RipleyResult v) { this.ripley = v; }
    public boolean hasRipley() { return ripley != null; }

    public GearyCResult getGeary() { return geary; }
    public void setGeary(GearyCResult v) { this.geary = v; }
    public boolean hasGeary() { return geary != null; }

    public CoOccurrenceResult getCoOccurrencePairwise() { return coOccurrencePairwise; }
    public void setCoOccurrencePairwise(CoOccurrenceResult v) { this.coOccurrencePairwise = v; }
    public boolean hasCoOccurrencePairwise() { return coOccurrencePairwise != null; }

    public CoOccurrenceResult getCoOccurrenceOneVsRest() { return coOccurrenceOneVsRest; }
    public void setCoOccurrenceOneVsRest(CoOccurrenceResult v) { this.coOccurrenceOneVsRest = v; }
    public boolean hasCoOccurrenceOneVsRest() { return coOccurrenceOneVsRest != null; }

    public String getSpatialGraphType() { return spatialGraphType; }
    public void setSpatialGraphType(String v) { this.spatialGraphType = v; }

    /**
     * True if at least one v1 spatial statistic is populated. Used by the
     * results-dialog tab-builder to decide whether the new tabs should
     * appear at all.
     */
    public boolean hasAnySpatialStats() {
        return hasRipley() || hasGeary()
                || hasCoOccurrencePairwise() || hasCoOccurrenceOneVsRest();
    }
}
