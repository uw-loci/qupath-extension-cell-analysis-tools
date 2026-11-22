package qupath.ext.qpcat.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
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
        /**
         * Class conditions, one nullable list per {@link ClassConditionKind} so a
         * saved file carries only the kinds in use and one written before a kind
         * existed reads as "no condition". Access through
         * {@link #getClassConditions()} / {@link #setClassCondition}.
         */
        private List<String> classIs;
        private List<String> classIsNot;
        private List<String> classHas;
        private List<String> classLacks;

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

        /**
         * The class conditions by kind.
         *
         * @return a map with every kind present; a kind not in the file is an empty list
         */
        public Map<ClassConditionKind, List<String>> getClassConditions() {
            Map<ClassConditionKind, List<String>> m = new EnumMap<>(ClassConditionKind.class);
            m.put(ClassConditionKind.IS, listOrEmpty(classIs));
            m.put(ClassConditionKind.IS_NOT, listOrEmpty(classIsNot));
            m.put(ClassConditionKind.HAS, listOrEmpty(classHas));
            m.put(ClassConditionKind.LACKS, listOrEmpty(classLacks));
            return m;
        }

        /**
         * Set the names for one kind; an empty or null list removes the kind from the file.
         *
         * @param kind which condition
         * @param names class names or components; copied
         */
        public void setClassCondition(ClassConditionKind kind, List<String> names) {
            List<String> v = names == null || names.isEmpty() ? null : new ArrayList<>(names);
            switch (kind) {
                case IS -> classIs = v;
                case IS_NOT -> classIsNot = v;
                case HAS -> classHas = v;
                case LACKS -> classLacks = v;
            }
        }

        /**
         * Set every kind at once.
         *
         * @param conditions per-kind names; a missing kind clears that kind
         */
        public void setClassConditions(Map<ClassConditionKind, List<String>> conditions) {
            for (ClassConditionKind k : ClassConditionKind.values()) {
                setClassCondition(k, conditions == null ? null : conditions.get(k));
            }
        }

        private static List<String> listOrEmpty(List<String> l) {
            return l == null ? new ArrayList<>() : new ArrayList<>(l);
        }
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
