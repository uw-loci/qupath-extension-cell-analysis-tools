package qupath.ext.qpcat.model;

import java.util.List;
import java.util.Map;

/**
 * Holds Ripley's K and L statistic curves per cluster.
 * <p>
 * Each {@link #clusterNames} entry has a matching K(r) and L(r) curve in
 * {@link #kValues} and {@link #lValues}. {@link #radii} is the shared
 * r-axis (length == K[i].length for every i). The {@link #poissonK} and
 * {@link #poissonL} arrays are the analytical null curves; they share the
 * same r-axis. {@code pValues} carries the per-cluster permutation p-value
 * (NaN when permutations were not run); {@code nPermutations} records the
 * actual count used (matches squidpy's {@code n_simulations}).
 * <p>
 * Gson-friendly POJO -- no behaviour, just accessors.
 */
public class RipleyResult {

    private List<String> clusterNames;
    private double[] radii;
    private double[][] kValues;       // [cluster][r]
    private double[][] lValues;       // [cluster][r]
    private double[] poissonK;        // analytical null K(r)
    private double[] poissonL;        // analytical null L(r) (zero line)
    private Map<String, Double> pValues;
    private int nPermutations = -1;
    private String graphType;         // "knn" | "radius" | "delaunay" used to build the graph

    public RipleyResult() {}

    public List<String> getClusterNames() { return clusterNames; }
    public void setClusterNames(List<String> v) { this.clusterNames = v; }

    public double[] getRadii() { return radii; }
    public void setRadii(double[] v) { this.radii = v; }

    public double[][] getKValues() { return kValues; }
    public void setKValues(double[][] v) { this.kValues = v; }

    public double[][] getLValues() { return lValues; }
    public void setLValues(double[][] v) { this.lValues = v; }

    public double[] getPoissonK() { return poissonK; }
    public void setPoissonK(double[] v) { this.poissonK = v; }

    public double[] getPoissonL() { return poissonL; }
    public void setPoissonL(double[] v) { this.poissonL = v; }

    public Map<String, Double> getPValues() { return pValues; }
    public void setPValues(Map<String, Double> v) { this.pValues = v; }

    public int getNPermutations() { return nPermutations; }
    public void setNPermutations(int v) { this.nPermutations = v; }

    public String getGraphType() { return graphType; }
    public void setGraphType(String v) { this.graphType = v; }

    /**
     * Number of cluster curves carried. Returns 0 when not yet populated.
     */
    public int pairCount() {
        return clusterNames == null ? 0 : clusterNames.size();
    }
}
