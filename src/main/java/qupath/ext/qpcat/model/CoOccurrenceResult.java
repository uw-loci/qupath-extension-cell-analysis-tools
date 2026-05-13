package qupath.ext.qpcat.model;

import java.util.List;

/**
 * Holds squidpy {@code co_occurrence} output. The data tensor is shaped
 * [clusterA][clusterB][interval] for the pairwise mode, or
 * [cluster][1][interval] for the one-vs-rest mode (clusterB axis is
 * length 1 and indexed by the synthetic "rest" label).
 * <p>
 * {@link #mode} mirrors the Python option key: {@code "pairwise"} or
 * {@code "oneVsRest"}. {@link #intervals} is the radius vector in pixel
 * units of detection centroids (length == data[i][j].length).
 * <p>
 * Gson-friendly POJO; older saves load with null fields.
 */
public class CoOccurrenceResult {

    private String mode;                  // "pairwise" | "oneVsRest"
    private List<String> clusterNames;    // axis 0 (and axis 1 in pairwise mode)
    private double[] intervals;           // radius bins (length == nIntervals)
    private double[][][] data;            // observed/expected ratio per (a, b, r)
    private double[][] pValues;           // [a][b] permutation p-values; null when not run
    private int nPermutations = -1;
    private String graphType;

    public CoOccurrenceResult() {}

    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }

    public List<String> getClusterNames() { return clusterNames; }
    public void setClusterNames(List<String> v) { this.clusterNames = v; }

    public double[] getIntervals() { return intervals; }
    public void setIntervals(double[] v) { this.intervals = v; }

    public double[][][] getData() { return data; }
    public void setData(double[][][] v) { this.data = v; }

    public double[][] getPValues() { return pValues; }
    public void setPValues(double[][] v) { this.pValues = v; }

    public int getNPermutations() { return nPermutations; }
    public void setNPermutations(int v) { this.nPermutations = v; }

    public String getGraphType() { return graphType; }
    public void setGraphType(String v) { this.graphType = v; }

    /**
     * Number of cells the analysis ran on. Returns 0 when not yet populated.
     */
    public int cellCount() {
        // Cell count is not retained on the result by design (squidpy
        // collapses across cells into the ratio tensor). Callers can
        // derive the cell count from the parent ClusteringResult.
        return clusterNames == null ? 0 : clusterNames.size();
    }
}
