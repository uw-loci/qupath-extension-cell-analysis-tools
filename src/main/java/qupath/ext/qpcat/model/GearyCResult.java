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

    public GearyCResult() {}

    public Map<String, Entry> getMarkerStats() { return markerStats; }
    public void setMarkerStats(Map<String, Entry> v) { this.markerStats = v; }

    public int getNPermutations() { return nPermutations; }
    public void setNPermutations(int v) { this.nPermutations = v; }

    public String getGraphType() { return graphType; }
    public void setGraphType(String v) { this.graphType = v; }

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
        if (markerStats == null) markerStats = new LinkedHashMap<>();
        markerStats.put(marker, new Entry(c, pValue));
    }

    /**
     * Per-marker Geary's C + p-value pair.
     */
    public static class Entry {
        private double c;
        private double pValue;

        public Entry() {}

        public Entry(double c, double pValue) {
            this.c = c;
            this.pValue = pValue;
        }

        public double getC() { return c; }
        public void setC(double v) { this.c = v; }

        public double getPValue() { return pValue; }
        public void setPValue(double v) { this.pValue = v; }
    }
}
