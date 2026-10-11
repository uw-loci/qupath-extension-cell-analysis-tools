package qupath.ext.qpcat.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds Geary's C per marker. Geary's C ranges from 0 to ~2 under the
 * null of spatial randomness: values below 1 indicate positive
 * autocorrelation (similar values clump together) and values above 1
 * indicate dispersion.
 * <p>
 * Gson-friendly POJO; mirrors the shape of the existing Moran's I
 * per-marker map but kept as a typed field set rather than a free-form
 * JSON string so callers can iterate markers directly.
 */
public class GearyCResult {

    private Map<String, Entry> markerStats;
    private int nPermutations = -1;
    private String graphType;
    private String pValueMethod;
    // Consistency across independent areas: C recomputed per area, the cut
    // that counted an area as structured, and one line on how many voted.
    private Map<String, Map<String, Double>> perArea;
    private Double threshold;
    private String areas;

    public GearyCResult() {}

    public Map<String, Entry> getMarkerStats() { return markerStats; }
    public void setMarkerStats(Map<String, Entry> v) { this.markerStats = v; }

    public int getNPermutations() { return nPermutations; }
    public void setNPermutations(int v) { this.nPermutations = v; }

    public String getGraphType() { return graphType; }
    public void setGraphType(String v) { this.graphType = v; }

    /**
     * Which of squidpy's p-value columns {@link Entry#getPValue()} holds, in
     * words. Null for a result saved before the method was recorded, when it
     * was always the uncorrected normal-theory value and the permutations the
     * run paid for were discarded.
     */
    public String getPValueMethod() { return pValueMethod; }
    public void setPValueMethod(String v) { this.pValueMethod = v; }

    /** {marker: {area label: C}} for each area that was evaluated on its own; null on older saves. */
    public Map<String, Map<String, Double>> getPerArea() { return perArea; }
    public void setPerArea(Map<String, Map<String, Double>> v) { this.perArea = v; }

    /** An area counted as structured when C <= 1 - threshold; null on older saves. */
    public Double getThreshold() { return threshold; }
    public void setThreshold(Double v) { this.threshold = v; }

    /** "8 area(s) evaluated, 1 skipped (...)"; null or empty when there was one area. */
    public String getAreas() { return areas; }
    public void setAreas(String v) { this.areas = v; }

    /**
     * Number of markers carried. Returns 0 when not yet populated.
     */
    public int measurementCount() {
        return markerStats == null ? 0 : markerStats.size();
    }

    /**
     * Convenience builder used by the JSON-deserialiser path.
     */
    public void putMarker(String marker, double c, double pValue) {
        putMarker(marker, c, pValue, null);
    }

    /**
     * @param marker measurement name
     * @param c Geary's C
     * @param pValue the reported p-value, see {@link #getPValueMethod()}
     * @param pValueUncorrected the same test without multiple-marker correction,
     *                          or null when it was not recorded
     */
    public void putMarker(String marker, double c, double pValue, Double pValueUncorrected) {
        putMarker(marker, c, pValue, pValueUncorrected, null, null);
    }

    /**
     * @param areasAbove areas whose own C was at or below 1 - threshold, or null when not counted
     * @param nAreas     areas that were evaluated on their own (0 or null for a single area)
     */
    public void putMarker(String marker, double c, double pValue, Double pValueUncorrected,
                          Integer areasAbove, Integer nAreas) {
        if (markerStats == null) markerStats = new LinkedHashMap<>();
        Entry e = new Entry(c, pValue, pValueUncorrected);
        e.setAreasAbove(areasAbove);
        e.setNAreas(nAreas);
        markerStats.put(marker, e);
    }

    /**
     * Per-marker Geary's C + p-value pair.
     */
    public static class Entry {
        private double c;
        private double pValue;
        // Boxed, and null when absent, NOT NaN: these objects are serialised
        // into a saved result with a plain Gson, which refuses NaN outright
        // ("NaN is not a valid double value as per JSON specification"). A NaN
        // default here made saving any result carrying Geary data throw.
        private Double pValueUncorrected;
        private Integer areasAbove;
        private Integer nAreas;

        public Entry() {}

        public Entry(double c, double pValue) {
            this(c, pValue, null);
        }

        public Entry(double c, double pValue, Double pValueUncorrected) {
            this.c = c;
            this.pValue = pValue;
            this.pValueUncorrected = pValueUncorrected;
        }

        public double getC() { return c; }
        public void setC(double v) { this.c = v; }

        public double getPValue() { return pValue; }
        public void setPValue(double v) { this.pValue = v; }

        /** The uncorrected p, or null when the run did not record one. */
        public Double getPValueUncorrected() { return pValueUncorrected; }
        public void setPValueUncorrected(Double v) { this.pValueUncorrected = v; }

        /** Areas whose own C passed the threshold, or null when the run did not count. */
        public Integer getAreasAbove() { return areasAbove; }
        public void setAreasAbove(Integer v) { this.areasAbove = v; }

        /** Areas evaluated on their own, or null when the run did not count. */
        public Integer getNAreas() { return nAreas; }
        public void setNAreas(Integer v) { this.nAreas = v; }
    }
}
