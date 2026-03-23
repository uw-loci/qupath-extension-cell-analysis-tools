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
}
