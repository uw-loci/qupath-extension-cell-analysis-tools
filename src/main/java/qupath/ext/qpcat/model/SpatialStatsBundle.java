package qupath.ext.qpcat.model;

/**
 * Optional persisted bundle of v1 spatial statistics expansion results.
 * Held on {@link SavedClusteringResult#getSpatialStats()}; absent on
 * saves predating v1, in which case Gson populates {@code null} for the
 * field and the existing tabs render as before.
 * <p>
 * The graph constructor parameters used to compute these results are
 * carried alongside the result tensors so the audit trail is
 * self-contained when the saved JSON is archived separately from the
 * day-stamped log file.
 */
public class SpatialStatsBundle {

    private String graphType;              // "knn" | "radius" | "delaunay"
    private int graphK;                    // -1 if not applicable
    private double graphRadius;            // -1 if not applicable / auto
    private double graphDelaunayMaxEdge;   // -1 if not applicable / no pruning
    private int nPermutations;             // value actually used (post-adaptive resolution)

    private RipleyResult ripley;
    private GearyCResult geary;
    private CoOccurrenceResult coOccurrencePairwise;
    private CoOccurrenceResult coOccurrenceOneVsRest;

    public SpatialStatsBundle() {}

    public String getGraphType() { return graphType; }
    public void setGraphType(String v) { this.graphType = v; }

    public int getGraphK() { return graphK; }
    public void setGraphK(int v) { this.graphK = v; }

    public double getGraphRadius() { return graphRadius; }
    public void setGraphRadius(double v) { this.graphRadius = v; }

    public double getGraphDelaunayMaxEdge() { return graphDelaunayMaxEdge; }
    public void setGraphDelaunayMaxEdge(double v) { this.graphDelaunayMaxEdge = v; }

    public int getNPermutations() { return nPermutations; }
    public void setNPermutations(int v) { this.nPermutations = v; }

    /** Per-area curves for a partitioned run; see ClusteringResult.getRipleyByArea(). */
    private java.util.Map<String, RipleyResult> ripleyByArea;

    public RipleyResult getRipley() { return ripley; }
    public void setRipley(RipleyResult v) { this.ripley = v; }

    public java.util.Map<String, RipleyResult> getRipleyByArea() { return ripleyByArea; }

    public void setRipleyByArea(java.util.Map<String, RipleyResult> v) { this.ripleyByArea = v; }

    public GearyCResult getGeary() { return geary; }
    public void setGeary(GearyCResult v) { this.geary = v; }

    private java.util.Map<String, CoOccurrenceResult> coOccurrencePairwiseByArea;
    private java.util.Map<String, CoOccurrenceResult> coOccurrenceOneVsRestByArea;

    public CoOccurrenceResult getCoOccurrencePairwise() { return coOccurrencePairwise; }
    public void setCoOccurrencePairwise(CoOccurrenceResult v) { this.coOccurrencePairwise = v; }

    public java.util.Map<String, CoOccurrenceResult> getCoOccurrencePairwiseByArea() {
        return coOccurrencePairwiseByArea;
    }

    public void setCoOccurrencePairwiseByArea(java.util.Map<String, CoOccurrenceResult> v) {
        this.coOccurrencePairwiseByArea = v;
    }

    public CoOccurrenceResult getCoOccurrenceOneVsRest() { return coOccurrenceOneVsRest; }
    public void setCoOccurrenceOneVsRest(CoOccurrenceResult v) { this.coOccurrenceOneVsRest = v; }

    public java.util.Map<String, CoOccurrenceResult> getCoOccurrenceOneVsRestByArea() {
        return coOccurrenceOneVsRestByArea;
    }

    public void setCoOccurrenceOneVsRestByArea(java.util.Map<String, CoOccurrenceResult> v) {
        this.coOccurrenceOneVsRestByArea = v;
    }

    /**
     * True if at least one of the four statistic slots is populated.
     */
    public boolean isAnyPresent() {
        return ripley != null || (ripleyByArea != null && !ripleyByArea.isEmpty())
                || (coOccurrencePairwiseByArea != null && !coOccurrencePairwiseByArea.isEmpty())
                || (coOccurrenceOneVsRestByArea != null && !coOccurrenceOneVsRestByArea.isEmpty())
                || geary != null
                || coOccurrencePairwise != null
                || coOccurrenceOneVsRest != null;
    }
}
