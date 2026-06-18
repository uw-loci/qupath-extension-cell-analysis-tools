package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.Collections;
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

    // Per-cell back-references (index-aligned with clusterLabels / embedding),
    // used to navigate to / crop the cell. Transient on the live result; the
    // persisted form reconstructs these from primitive arrays in
    // SavedClusteringResult.
    private CellRef[] cellRefs;

    // Representative-cell indices per cluster, as returned by run_clustering.py.
    // JSON shape: { "<cluster>": { "feature": [idx,...], "embedding": [idx,...] } }.
    // Indices are into the same cell order as clusterLabels / cellRefs.
    private String representativesJson;
    private transient Map<String, Map<String, java.util.List<Double>>> representativesParsed;

    // Auto-save bookkeeping (transient; set by ClusteringWorkflow after the
    // result is persisted to <project>/qpcat/cluster_results/). Lets the
    // results dialog show where the data landed, its on-disk size, and how
    // many saved results now exist for this scope (for the over-5 warning).
    private transient String savedName;
    private transient String savedPath;
    private transient long savedSizeBytes = -1;
    private transient int savedScopeCount = -1;
    private transient String savedScopeLabel;

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

    // --- Per-cell back-references ---
    public CellRef[] getCellRefs() { return cellRefs; }
    public void setCellRefs(CellRef[] refs) { this.cellRefs = refs; }
    public boolean hasCellRefs() { return cellRefs != null && cellRefs.length > 0; }

    // --- Auto-save bookkeeping ---
    public String getSavedName() { return savedName; }
    public void setSavedName(String savedName) { this.savedName = savedName; }

    public String getSavedPath() { return savedPath; }
    public void setSavedPath(String savedPath) { this.savedPath = savedPath; }

    public long getSavedSizeBytes() { return savedSizeBytes; }
    public void setSavedSizeBytes(long savedSizeBytes) { this.savedSizeBytes = savedSizeBytes; }

    public int getSavedScopeCount() { return savedScopeCount; }
    public void setSavedScopeCount(int savedScopeCount) { this.savedScopeCount = savedScopeCount; }

    public String getSavedScopeLabel() { return savedScopeLabel; }
    public void setSavedScopeLabel(String savedScopeLabel) { this.savedScopeLabel = savedScopeLabel; }

    // --- Representative cells ---
    public String getRepresentativesJson() { return representativesJson; }
    public void setRepresentativesJson(String json) {
        this.representativesJson = json;
        this.representativesParsed = null;  // invalidate cache
    }
    public boolean hasRepresentatives() {
        return representativesJson != null && !representativesJson.isBlank();
    }

    /**
     * Representative-cell indices for one cluster under the requested space.
     * The first index (when present) is the medoid.
     *
     * @param cluster   cluster id
     * @param embedding true for embedding-space medoids, false for feature-space
     * @return ordered cell indices (into clusterLabels / cellRefs); empty if none
     */
    public int[] getRepresentativeIndices(int cluster, boolean embedding) {
        if (!hasRepresentatives()) return new int[0];
        if (representativesParsed == null) {
            try {
                representativesParsed = new Gson().fromJson(representativesJson,
                        new TypeToken<Map<String, Map<String, java.util.List<Double>>>>(){}.getType());
            } catch (Exception e) {
                representativesParsed = Collections.emptyMap();
            }
        }
        Map<String, java.util.List<Double>> perCluster =
                representativesParsed.get(String.valueOf(cluster));
        if (perCluster == null) return new int[0];
        java.util.List<Double> idx = perCluster.get(embedding ? "embedding" : "feature");
        if (idx == null || idx.isEmpty()) return new int[0];
        int[] out = new int[idx.size()];
        for (int i = 0; i < out.length; i++) out[i] = (int) Math.round(idx.get(i));
        return out;
    }

    /** True if embedding-space representatives are available for any cluster. */
    public boolean hasEmbeddingRepresentatives() {
        if (!hasEmbedding()) return false;
        for (int c = 0; c < nClusters; c++) {
            if (getRepresentativeIndices(c, true).length > 0) return true;
        }
        return false;
    }

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
         *
         * <p><strong>v0.3.4 (D1 fix):</strong> per-cell aggregates
         * (numNeighbors, mean/median/max/min distance, triangle areas)
         * are intentionally <em>left null</em> on the sliced output.
         * The global values are not correct per-image: a cell at
         * pixel (100, 100) in image A and a cell at (100, 100) in image
         * B both contribute neighbours to each other in the global
         * graph (extractMultiImage concatenates per-image coordinates
         * without offset), so the global numNeighbors counts include
         * phantom cross-image edges and the global distance aggregates
         * are computed over those phantom edges too. The
         * single-image path bypasses {@code slice} and keeps the
         * verbatim aggregates (correct because there is only one
         * segment). The project path's {@code applySpatialGraphPayload}
         * detects the null aggregates and recomputes them from the
         * sliced edge COO + per-image centroids. Triangle areas remain
         * null on multi-image (recomputing would require re-running
         * the Delaunay triangulation per-image, which is expensive
         * enough that we defer it to a future release).</p>
         */
        public SpatialGraphPayload slice(int start, int end) {
            SpatialGraphPayload out = new SpatialGraphPayload();
            int n = end - start;
            // D1: per-cell aggregates intentionally NOT copied (see Javadoc).
            // Component labels are graph-build-time outputs; component
            // membership for any cell that lost all its edges to the
            // cross-image slice is still recoverable, so we keep these.
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
    }
}
