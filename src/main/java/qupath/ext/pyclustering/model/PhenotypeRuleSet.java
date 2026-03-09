package qupath.ext.pyclustering.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named, persistable set of phenotype rules with per-marker gate thresholds.
 * Serialized to JSON for save/load within a QuPath project.
 */
public class PhenotypeRuleSet {

    private int version = 1;
    private String name;
    private String created;
    private String modified;
    private String normalization;
    private List<String> markers;
    private Map<String, Double> gates;
    private List<RuleEntry> rules;

    public PhenotypeRuleSet() {
        this.markers = new ArrayList<>();
        this.gates = new LinkedHashMap<>();
        this.rules = new ArrayList<>();
        this.created = Instant.now().toString();
        this.modified = this.created;
    }

    public PhenotypeRuleSet(String name) {
        this();
        this.name = name;
    }

    /**
     * A single phenotype rule: a cell type name and marker conditions.
     */
    public static class RuleEntry {
        private String cellType;
        private Map<String, String> conditions;

        public RuleEntry() {
            this.conditions = new LinkedHashMap<>();
        }

        public RuleEntry(String cellType, Map<String, String> conditions) {
            this.cellType = cellType;
            this.conditions = conditions != null ? new LinkedHashMap<>(conditions) : new LinkedHashMap<>();
        }

        public String getCellType() { return cellType; }
        public void setCellType(String cellType) { this.cellType = cellType; }
        public Map<String, String> getConditions() { return conditions; }
        public void setConditions(Map<String, String> conditions) { this.conditions = conditions; }
    }

    // Getters and setters

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }

    public String getModified() { return modified; }
    public void setModified(String modified) { this.modified = modified; }

    public String getNormalization() { return normalization; }
    public void setNormalization(String normalization) { this.normalization = normalization; }

    public List<String> getMarkers() { return markers; }
    public void setMarkers(List<String> markers) { this.markers = markers; }

    public Map<String, Double> getGates() { return gates; }
    public void setGates(Map<String, Double> gates) { this.gates = gates; }

    public List<RuleEntry> getRules() { return rules; }
    public void setRules(List<RuleEntry> rules) { this.rules = rules; }

    /** Update the modified timestamp to now. */
    public void touch() {
        this.modified = Instant.now().toString();
    }
}
