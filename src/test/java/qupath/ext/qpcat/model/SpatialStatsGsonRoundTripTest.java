package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gson round-trip invariants for the v1 spatial-stats result classes.
 * The on-disk SavedClusteringResult schema depends on these classes
 * surviving a serialise / deserialise cycle byte-for-byte; this test
 * fails if a field is renamed without a custom adapter to migrate
 * older saves.
 */
class SpatialStatsGsonRoundTripTest {

    private final Gson gson = new Gson();

    @Test
    void ripleyResultRoundTripsThroughGson() {
        RipleyResult original = new RipleyResult();
        original.setClusterNames(List.of("0", "1"));
        original.setRadii(new double[]{1.0, 2.0, 3.0});
        original.setKValues(new double[][]{{0.1, 0.4, 0.9}, {0.2, 0.3, 0.4}});
        original.setLValues(new double[][]{{0.0, 0.0, 0.0}, {0.05, 0.07, 0.09}});
        original.setPoissonK(new double[]{Math.PI, 4 * Math.PI, 9 * Math.PI});
        original.setPoissonL(new double[]{0.0, 0.0, 0.0});
        Map<String, Double> pvs = new LinkedHashMap<>();
        pvs.put("0", 0.01);
        pvs.put("1", 0.20);
        original.setPValues(pvs);
        original.setNPermutations(100);
        original.setGraphType("knn");

        String json = gson.toJson(original);
        RipleyResult restored = gson.fromJson(json, RipleyResult.class);

        assertThat(restored.getClusterNames()).containsExactly("0", "1");
        assertThat(restored.getRadii()).containsExactly(1.0, 2.0, 3.0);
        assertThat(restored.getKValues()).hasDimensions(2, 3);
        assertThat(restored.getLValues()).hasDimensions(2, 3);
        assertThat(restored.getPoissonK()).hasSize(3);
        assertThat(restored.getPValues()).containsEntry("0", 0.01);
        assertThat(restored.getNPermutations()).isEqualTo(100);
        assertThat(restored.getGraphType()).isEqualTo("knn");
    }

    @Test
    void gearyResultRoundTripsThroughGson() {
        GearyCResult original = new GearyCResult();
        original.putMarker("CD3: Mean", 0.42, 0.001);
        original.putMarker("CD8: Mean", 0.55, 0.020);
        original.setNPermutations(1000);
        original.setGraphType("radius");

        String json = gson.toJson(original);
        GearyCResult restored = gson.fromJson(json, GearyCResult.class);

        assertThat(restored.measurementCount()).isEqualTo(2);
        assertThat(restored.getMarkerStats().get("CD3: Mean").getC()).isEqualTo(0.42);
        assertThat(restored.getMarkerStats().get("CD8: Mean").getPValue()).isEqualTo(0.020);
        assertThat(restored.getNPermutations()).isEqualTo(1000);
        assertThat(restored.getGraphType()).isEqualTo("radius");
    }

    @Test
    void coOccurrenceResultRoundTripsThroughGson() {
        CoOccurrenceResult original = new CoOccurrenceResult();
        original.setMode("pairwise");
        original.setClusterNames(List.of("0", "1"));
        original.setIntervals(new double[]{10.0, 20.0});
        original.setData(new double[][][]{
                {{1.4, 0.9}, {0.8, 1.1}},
                {{0.8, 1.1}, {1.5, 1.3}},
        });
        original.setNPermutations(100);
        original.setGraphType("delaunay");

        String json = gson.toJson(original);
        CoOccurrenceResult restored = gson.fromJson(json, CoOccurrenceResult.class);

        assertThat(restored.getMode()).isEqualTo("pairwise");
        assertThat(restored.getIntervals()).hasSize(2);
        assertThat(restored.getData()).hasDimensions(2, 2);
        assertThat(restored.getData()[0][0]).containsExactly(1.4, 0.9);
        assertThat(restored.getGraphType()).isEqualTo("delaunay");
    }

    @Test
    void spatialStatsBundleRoundTripsThroughGson() {
        SpatialStatsBundle original = new SpatialStatsBundle();
        original.setGraphType("knn");
        original.setGraphK(15);
        original.setNPermutations(100);

        GearyCResult geary = new GearyCResult();
        geary.putMarker("CD3", 0.5, 0.01);
        original.setGeary(geary);

        String json = gson.toJson(original);
        SpatialStatsBundle restored = gson.fromJson(json, SpatialStatsBundle.class);

        assertThat(restored.getGraphType()).isEqualTo("knn");
        assertThat(restored.getGraphK()).isEqualTo(15);
        assertThat(restored.getNPermutations()).isEqualTo(100);
        assertThat(restored.getGeary()).isNotNull();
        assertThat(restored.getGeary().measurementCount()).isEqualTo(1);
        assertThat(restored.isAnyPresent()).isTrue();
    }

    @Test
    void savedClusteringResultPreservesNullSpatialStatsBundle() {
        // Older saves predate the spatialStats field; deserialising should
        // leave it null without crashing, matching the existing pattern
        // for the LLM-explainer fields (LlmExplanationsBundle).
        String legacyJson = "{\"name\":\"old-result\",\"nClusters\":3,\"nCells\":1000}";
        SavedClusteringResult restored = gson.fromJson(legacyJson,
                SavedClusteringResult.class);

        assertThat(restored.getName()).isEqualTo("old-result");
        assertThat(restored.getSpatialStats()).isNull();
        assertThat(restored.hasSpatialStats()).isFalse();
    }
}
