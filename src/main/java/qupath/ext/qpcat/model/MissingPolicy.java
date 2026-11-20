package qupath.ext.qpcat.model;

import java.util.Locale;

/**
 * What a run does with an object that lacks a value for a selected measurement
 * (issue #29). InstanSeg leaves cells without a nucleus, so every {@code Nucleus:}
 * measurement is missing on them; a project can also mix objects that are not
 * cells. Until 0.25.0 every such value was replaced by the column median without
 * telling anyone.
 */
public enum MissingPolicy {
    /** Leave the objects out of the run; they stay unclassified and are counted. The default. */
    EXCLUDE_OBJECTS("exclude_objects", "Exclude objects with missing measurements (leave unclassified)"),
    /** Leave the measurements with a missing value out of the run; every object stays. */
    EXCLUDE_MEASUREMENTS("exclude_measurements", "Exclude measurements containing missing values (keep all objects)"),
    /** Replace each missing value with the column's median of the finite values (the pre-0.25.0 behavior). */
    IMPUTE("impute", "Replace missing values with the median");

    private final String id;
    private final String label;

    MissingPolicy(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** The YAML and saved-result id. */
    public String getId() { return id; }

    /** The dialog label. */
    public String getLabel() { return label; }

    /**
     * @param id a YAML id such as {@code exclude_objects}; case does not matter
     * @return the policy
     * @throws IllegalArgumentException for an unknown id
     */
    public static MissingPolicy fromId(String id) {
        if (id != null) {
            String s = id.trim().toLowerCase(Locale.ROOT);
            for (MissingPolicy p : values()) {
                if (p.id.equals(s)) return p;
            }
        }
        throw new IllegalArgumentException("Unknown missing-values policy: " + id);
    }
}
