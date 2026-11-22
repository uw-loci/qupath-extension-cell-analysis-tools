package qupath.ext.qpcat.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The four ways a phenotyping rule can test the class a cell already carries.
 * <p>
 * {@link #IS} and {@link #IS_NOT} match the whole class name. {@link #HAS} and
 * {@link #LACKS} match one component of a derived class: QuPath's composite
 * classifiers write {@code "CD45: Ki67"} for a cell positive for both, so a rule
 * for "CD45+ and Ki67-" is {@code HAS CD45, LACKS Ki67} whatever the rest of the
 * panel. Each kind carries the key it uses in the rule JSON sent to the worker,
 * the key in a batch YAML file and the label shown in the dialog.
 */
public enum ClassConditionKind {
    /** The whole class name must be one of the listed names. */
    IS("classIs", "class_is", "is"),
    /** The whole class name must not be any of the listed names. */
    IS_NOT("classIsNot", "class_is_not", "is not"),
    /** Every listed component must be a component of the class. */
    HAS("classHas", "class_has", "has component"),
    /** No listed component may be a component of the class. */
    LACKS("classLacks", "class_lacks", "lacks component");

    /** Separator between the components of a QuPath derived class ({@code "CD45: Ki67"}). */
    public static final String COMPONENT_SEPARATOR = ": ";

    private final String jsonKey;
    private final String yamlKey;
    private final String label;

    ClassConditionKind(String jsonKey, String yamlKey, String label) {
        this.jsonKey = jsonKey;
        this.yamlKey = yamlKey;
        this.label = label;
    }

    /** @return the key in the rule JSON the worker evaluates ({@code classIs}, ...) */
    public String jsonKey() { return jsonKey; }

    /** @return the key in a batch YAML rule ({@code class_is}, ...) */
    public String yamlKey() { return yamlKey; }

    /** @return the short label the dialog shows ("is", "has component", ...) */
    public String label() { return label; }

    /** @return true for the kinds that match components rather than whole names */
    public boolean matchesComponents() { return this == HAS || this == LACKS; }

    /**
     * The kind using a batch YAML key.
     *
     * @param yamlKey a rule key such as {@code class_has}
     * @return the kind, or null when the key is not a class condition
     */
    public static ClassConditionKind fromYamlKey(String yamlKey) {
        for (ClassConditionKind k : values()) {
            if (k.yamlKey.equals(yamlKey)) return k;
        }
        return null;
    }

    /** @return every kind's YAML key, in declaration order */
    public static List<String> yamlKeys() {
        List<String> keys = new ArrayList<>();
        for (ClassConditionKind k : values()) keys.add(k.yamlKey);
        return keys;
    }

    /**
     * A map holding an empty, mutable list for every kind.
     *
     * @return a fresh map with all four kinds present
     */
    public static Map<ClassConditionKind, List<String>> emptyConditions() {
        Map<ClassConditionKind, List<String>> m = new EnumMap<>(ClassConditionKind.class);
        for (ClassConditionKind k : values()) m.put(k, new ArrayList<>());
        return m;
    }

    /**
     * True when any kind in the map names at least one class.
     *
     * @param conditions per-kind names; missing kinds count as empty
     * @return whether the rule has a class condition at all
     */
    public static boolean anyPresent(Map<ClassConditionKind, List<String>> conditions) {
        if (conditions == null) return false;
        for (List<String> names : conditions.values()) {
            if (names != null && !names.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Write the non-empty conditions into a rule map under their JSON keys, the
     * shape {@code run_phenotyping.py} reads. Empty kinds are left out so a rule
     * without class conditions carries no class keys.
     *
     * @param ruleMap the rule being serialized; modified
     * @param conditions per-kind names; null or missing kinds are skipped
     */
    public static void putInto(Map<String, Object> ruleMap, Map<ClassConditionKind, List<String>> conditions) {
        if (conditions == null) return;
        for (ClassConditionKind k : values()) {
            List<String> names = conditions.get(k);
            if (names != null && !names.isEmpty()) ruleMap.put(k.jsonKey, new ArrayList<>(names));
        }
    }

    /**
     * The components of a class name, split on {@link #COMPONENT_SEPARATOR}: the
     * same parts {@code PathClass.toSet()} gives and the split the worker uses.
     *
     * @param className a class name such as {@code "CD45: Ki67"}; null or blank gives no components
     * @return the components in order, without duplicates
     */
    public static List<String> componentsOf(String className) {
        if (className == null || className.isBlank()) return List.of();
        Set<String> parts = new LinkedHashSet<>(Arrays.asList(className.split(COMPONENT_SEPARATOR, -1)));
        parts.remove("");
        return new ArrayList<>(parts);
    }
}
