package qupath.ext.pyclustering.model;

/**
 * Holds the results of a clustering run returned from Python.
 */
public class ClusteringResult {

    private final int[] clusterLabels;
    private final int nClusters;
    private final double[][] embedding;       // may be null if no embedding was computed
    private final double[][] clusterStats;    // per-cluster marker means (nClusters x nMarkers)
    private final String[] markerNames;

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
}
