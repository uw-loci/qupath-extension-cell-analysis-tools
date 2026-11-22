package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Every field of {@link ClusteringResult} either survives
 * {@code SavedClusteringResult.fromResult(...)} followed by
 * {@code toClusteringResult()}, or is named below with the reason it does not.
 *
 * <p>The two classes mirror each other field by field, so a field added to the
 * result and forgotten in {@code fromResult} silently does not persist. This
 * test fills every field by reflection, round-trips, and compares. A new field
 * fails here until it is copied or listed.</p>
 */
class ClusteringResultSaveParityTest {

    /** Deliberately not saved. Field name -> one-line reason. */
    private static final Map<String, String> TRANSIENT = Map.ofEntries(
            Map.entry("representativesParsed", "cache parsed from representativesJson, which is saved"),
            Map.entry("clusterNameDigits", "cache of the zero-padding width, recomputed from the labels"),
            Map.entry("savedName", "auto-save bookkeeping, set by the workflow after the save"),
            Map.entry("savedPath", "auto-save bookkeeping, set by the workflow after the save"),
            Map.entry("savedSizeBytes", "auto-save bookkeeping, set by the workflow after the save"),
            Map.entry("savedScopeCount", "auto-save bookkeeping, set by the workflow after the save"),
            Map.entry("savedScopeLabel", "auto-save bookkeeping, set by the workflow after the save"),
            Map.entry("spatialGraphPayload", "edge list and per-cell arrays, too large for JSON; "
                    + "written to the cells and the viewer instead"));

    /** Saved, but from an argument of {@code fromResult}, not from the result's own field. */
    private static final Map<String, String> SUPPLIED_BY_CALLER = Map.of(
            "normalization", "fromResult takes the config's normalization as an argument");

    private static final int N_CELLS = 3;

    private static List<Field> resultFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : ClusteringResult.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    /** A value for the field that differs from what a fresh result holds. */
    private static Object valueFor(Field f, int index) {
        String name = f.getName();
        Class<?> type = f.getType();
        if (type == int.class) return 100 + index;
        if (type == long.class) return 1000L + index;
        if (type == boolean.class) return true;
        if (type == Double.class) return 0.25 + index;
        if (type == String.class) return "value of " + name;
        if (type == String[].class) {
            // Three distinct names: enough for the "two or more classes / areas" gates.
            return new String[] {name + " a", name + " b", name + " c"};
        }
        if (type == int[].class) return new int[] {0, 1, 1};
        if (type == double[][].class) return new double[][] {{1, 2}, {3, 4 + index}};
        if (type == CellRef[].class) {
            return new CellRef[] {
                new CellRef("img-1", "one.tif", "id-a", 10.5, 20.5, 6),
                new CellRef("img-1", "one.tif", "id-b", 30.5, 40.5, 7),
                new CellRef("img-2", "two.tif", "id-c", 50.5, 60.5, 8)};
        }
        if (type == MembershipConfidence.class) {
            return new MembershipConfidence(MembershipConfidence.Kind.POSTERIOR,
                    new double[] {0.9, 0.8, 0.7}, new int[] {1, 0, 0},
                    new double[] {0.1, 0.2, 0.3}, new double[] {0.2, 0.3, 0.4});
        }
        if (type == InputSelection.class) {
            return new InputSelection(
                    List.of(new InputSelection.Annotation("Tumor 1", "Tumor", "ann-1", 42)), 0);
        }
        if (type == RipleyResult.class) return new RipleyResult();
        if (type == GearyCResult.class) return new GearyCResult();
        if (type == CoOccurrenceResult.class) return new CoOccurrenceResult();
        if (type == ClusteringResult.SpatialGraphPayload.class) {
            return new ClusteringResult.SpatialGraphPayload();
        }
        if (type == List.class) return new ArrayList<>(List.of("entry of " + name));
        if (type == Map.class) {
            Type[] args = ((ParameterizedType) f.getGenericType()).getActualTypeArguments();
            Map<Object, Object> map = new LinkedHashMap<>();
            Object key = args[0] == Integer.class ? (Object) 0 : "key of " + name;
            Object value;
            if (args[1] == String.class) value = "mapped by " + name;
            else if (args[1] == Double.class) value = 0.5 + index;
            else if (args[1] == RipleyResult.class) value = new RipleyResult();
            else if (args[1] == CoOccurrenceResult.class) value = new CoOccurrenceResult();
            else value = new LinkedHashMap<>(Map.of("feature", List.of(1.0)));
            map.put(key, value);
            return map;
        }
        return fail("ClusteringResult." + name + " has type " + type.getName()
                + ", which this test cannot fill yet: add it to valueFor()");
    }

    /** A result with every field set, and the values that were set. */
    private static ClusteringResult populated(Map<String, Object> values) throws Exception {
        List<Field> fields = resultFields();
        for (int i = 0; i < fields.size(); i++) {
            values.put(fields.get(i).getName(), valueFor(fields.get(i), i));
        }
        ClusteringResult result = new ClusteringResult(
                (int[]) values.get("clusterLabels"), 2,
                (double[][]) values.get("embedding"),
                (double[][]) values.get("clusterStats"),
                (String[]) values.get("markerNames"));
        values.put("nClusters", 2);
        for (Field f : fields) {
            if (!Modifier.isFinal(f.getModifiers())) {
                f.set(result, values.get(f.getName()));
            }
        }
        return result;
    }

    private static boolean same(Object expected, Object actual) {
        try {
            assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    /** Names of the fields whose value did not come back from a save and reload. */
    private static Set<String> lostInRoundTrip() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        ClusteringResult original = populated(values);
        assertThat(original.getNCells()).isEqualTo(N_CELLS);

        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                original, "parity", "kmeans", "normalization argument", "umap");
        ClusteringResult reloaded = saved.toClusteringResult();

        Set<String> lost = new TreeSet<>();
        for (Field f : resultFields()) {
            if (!same(values.get(f.getName()), f.get(reloaded))) {
                lost.add(f.getName());
            }
        }
        return lost;
    }

    @Test
    void everyFieldIsFilledWithANonDefaultValue() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        populated(values);
        ClusteringResult fresh = new ClusteringResult(new int[0], 0, null, null, null);
        for (Field f : resultFields()) {
            assertThat(same(f.get(fresh), values.get(f.getName())))
                    .as("the test value for %s must differ from the default", f.getName())
                    .isFalse();
        }
    }

    @Test
    void everyFieldIsSavedOrListedWithAReason() throws Exception {
        Set<String> accounted = new TreeSet<>();
        accounted.addAll(TRANSIENT.keySet());
        accounted.addAll(SUPPLIED_BY_CALLER.keySet());

        Set<String> unexplained = new TreeSet<>(lostInRoundTrip());
        unexplained.removeAll(accounted);
        assertThat(unexplained)
                .as("ClusteringResult fields that do not survive SavedClusteringResult.fromResult + "
                        + "toClusteringResult. Copy each in both methods, or add it to TRANSIENT "
                        + "with a reason")
                .isEmpty();
    }

    @Test
    void theListsHoldOnlyFieldsThatAreReallyNotSaved() throws Exception {
        Set<String> lost = lostInRoundTrip();
        Set<String> stale = new TreeSet<>();
        stale.addAll(TRANSIENT.keySet());
        stale.addAll(SUPPLIED_BY_CALLER.keySet());
        stale.removeAll(lost);
        assertThat(stale)
                .as("listed as not saved, but these now round-trip (or no longer exist): "
                        + "remove them from the list")
                .isEmpty();
    }

    @Test
    void theListsDoNotOverlapAndEveryTransientHasAReason() {
        Set<String> overlap = new TreeSet<>(TRANSIENT.keySet());
        overlap.retainAll(SUPPLIED_BY_CALLER.keySet());
        assertThat(overlap).isEmpty();
        assertThat(TRANSIENT.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void theCallersNormalizationIsWhatIsSaved() throws Exception {
        ClusteringResult original = populated(new LinkedHashMap<>());
        SavedClusteringResult saved = SavedClusteringResult.fromResult(
                original, "parity", "kmeans", "zscore", "umap");
        assertThat(saved.toClusteringResult().getNormalization()).isEqualTo("zscore");
    }
}
