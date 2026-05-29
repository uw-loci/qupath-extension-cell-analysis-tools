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

    // ==================== Spatial Graph Overlay (v0.3) ====================
    //
    // Transient payload carrying the edge COO plus per-cell measurement
    // arrays returned by the Python helper. NOT persisted to disk -- the
    // arrays are too large for the JSON round-trip and the QuPath
    // PathObjectConnections payload itself is reconstructed inline.

    private SpatialGraphPayload spatialGraphPayload;

    public SpatialGraphPayload getSpatialGraphPayload() { return spatialGraphPayload; }
    public void setSpatialGraphPayload(SpatialGraphPayload p) { this.spatialGraphPayload = p; }
    public boolean hasSpatialGraphPayload() { return spatialGraphPayload != null; }

    /**
     * Transient bundle of v0.3 spatial-graph outputs. Lives only on the
     * in-memory ClusteringResult so the workflow can materialise the
     * graph + per-cell measurements after the Appose task completes.
     */
    public static final class SpatialGraphPayload {
        private long[] edgeRow;             // undirected edge COO row index
        private long[] edgeCol;             // undirected edge COO column index
        private int[] numNeighbors;         // per-cell degree
        private double[] meanDistance;      // per-cell mean neighbor distance
        private double[] medianDistance;
        private double[] maxDistance;
        private double[] minDistance;
        private double[] meanTriangleArea;  // per-cell mean adjacent Delaunay triangle area; null when graph != delaunay
        private double[] maxTriangleArea;
        private int[] componentLabels;      // per-cell connected-component label; null when write_component_measurements off

        public long[] getEdgeRow() { return edgeRow; }
        public void setEdgeRow(long[] v) { this.edgeRow = v; }

        public long[] getEdgeCol() { return edgeCol; }
        public void setEdgeCol(long[] v) { this.edgeCol = v; }

        public int[] getNumNeighbors() { return numNeighbors; }
        public void setNumNeighbors(int[] v) { this.numNeighbors = v; }

        public double[] getMeanDistance() { return meanDistance; }
        public void setMeanDistance(double[] v) { this.meanDistance = v; }

        public double[] getMedianDistance() { return medianDistance; }
        public void setMedianDistance(double[] v) { this.medianDistance = v; }

        public double[] getMaxDistance() { return maxDistance; }
        public void setMaxDistance(double[] v) { this.maxDistance = v; }

        public double[] getMinDistance() { return minDistance; }
        public void setMinDistance(double[] v) { this.minDistance = v; }

        public double[] getMeanTriangleArea() { return meanTriangleArea; }
        public void setMeanTriangleArea(double[] v) { this.meanTriangleArea = v; }

        public double[] getMaxTriangleArea() { return maxTriangleArea; }
        public void setMaxTriangleArea(double[] v) { this.maxTriangleArea = v; }

        public int[] getComponentLabels() { return componentLabels; }
        public void setComponentLabels(int[] v) { this.componentLabels = v; }

        public boolean hasEdgeCoo() {
            return edgeRow != null && edgeCol != null
                    && edgeRow.length == edgeCol.length
                    && edgeRow.length > 0;
        }

        public boolean hasNodeMeasurements() { return numNeighbors != null; }

        public boolean hasTriangleAreas() {
            return meanTriangleArea != null && maxTriangleArea != null;
        }

        public boolean hasComponentLabels() { return componentLabels != null; }

        /**
         * Return a payload that covers only the cells in [start, end).
         * Used by the project-clustering path which combines multiple
         * images for a global graph build but applies results back per
         * image. Edge entries whose endpoints land outside the slice
         * are dropped; surviving endpoints are remapped to [0, end-start).
         */
        public SpatialGraphPayload slice(int start, int end) {
            SpatialGraphPayload out = new SpatialGraphPayload();
            int n = end - start;
            if (numNeighbors != null && numNeighbors.length >= end) {
                int[] arr = new int[n];
                System.arraycopy(numNeighbors, start, arr, 0, n);
                out.setNumNeighbors(arr);
            }
            out.setMeanDistance(sliceDouble(meanDistance, start, end));
            out.setMedianDistance(sliceDouble(medianDistance, start, end));
            out.setMaxDistance(sliceDouble(maxDistance, start, end));
            out.setMinDistance(sliceDouble(minDistance, start, end));
            out.setMeanTriangleArea(sliceDouble(meanTriangleArea, start, end));
            out.setMaxTriangleArea(sliceDouble(maxTriangleArea, start, end));
            if (componentLabels != null && componentLabels.length >= end) {
                int[] arr = new int[n];
                System.arraycopy(componentLabels, start, arr, 0, n);
                out.setComponentLabels(arr);
            }
            if (edgeRow != null && edgeCol != null) {
                int cap = edgeRow.length;
                long[] tmpRow = new long[cap];
                long[] tmpCol = new long[cap];
                int kept = 0;
                for (int e = 0; e < cap; e++) {
                    long r = edgeRow[e];
                    long c = edgeCol[e];
                    if (r >= start && r < end && c >= start && c < end) {
                        tmpRow[kept] = r - start;
                        tmpCol[kept] = c - start;
                        kept++;
                    }
                }
                if (kept > 0) {
                    long[] finalRow = new long[kept];
                    long[] finalCol = new long[kept];
                    System.arraycopy(tmpRow, 0, finalRow, 0, kept);
                    System.arraycopy(tmpCol, 0, finalCol, 0, kept);
                    out.setEdgeRow(finalRow);
                    out.setEdgeCol(finalCol);
                }
            }
            return out;
        }

        private static double[] sliceDouble(double[] src, int start, int end) {
            if (src == null || src.length < end) return null;
            double[] arr = new double[end - start];
            System.arraycopy(src, start, arr, 0, end - start);
            return arr;
        }
    }
}
