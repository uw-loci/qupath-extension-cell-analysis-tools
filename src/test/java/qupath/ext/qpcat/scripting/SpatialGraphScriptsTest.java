package qupath.ext.qpcat.scripting;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 invariants for the {@link SpatialGraphScripts} option-map
 * facade. The scripting API is part of the v1 stability surface
 * (package path, class name, method names, recognised option keys);
 * regressions here mean the documented Groovy contract has shifted.
 */
class SpatialGraphScriptsTest {

    @Test
    void normaliseTypeAcceptsCanonicalTokens() {
        assertThat(SpatialGraphScripts.normaliseType("knn")).isEqualTo("knn");
        assertThat(SpatialGraphScripts.normaliseType("RADIUS")).isEqualTo("radius");
        assertThat(SpatialGraphScripts.normaliseType("Delaunay")).isEqualTo("delaunay");
    }

    @Test
    void normaliseTypeFallsBackToKnnOnUnknown() {
        assertThat(SpatialGraphScripts.normaliseType("kdtree")).isEqualTo("knn");
        assertThat(SpatialGraphScripts.normaliseType(null)).isEqualTo("knn");
        assertThat(SpatialGraphScripts.normaliseType("")).isEqualTo("knn");
    }

    @Test
    void readIntCoercesNumbersAndStrings() {
        assertThat(SpatialGraphScripts.readInt(15, 0)).isEqualTo(15);
        assertThat(SpatialGraphScripts.readInt("20", 0)).isEqualTo(20);
        assertThat(SpatialGraphScripts.readInt(3.7, 0)).isEqualTo(3);
    }

    @Test
    void readIntFallsBackOnNullOrNonsense() {
        assertThat(SpatialGraphScripts.readInt(null, 42)).isEqualTo(42);
        assertThat(SpatialGraphScripts.readInt("abc", 7)).isEqualTo(7);
    }

    @Test
    void readDoubleCoercesNumbersAndStrings() {
        assertThat(SpatialGraphScripts.readDouble(2.5, 0.0)).isEqualTo(2.5);
        assertThat(SpatialGraphScripts.readDouble("3.14", 0.0)).isEqualTo(3.14);
        assertThat(SpatialGraphScripts.readDouble(5, 0.0)).isEqualTo(5.0);
    }

    @Test
    void buildGraphReturnsCanonicalKeysForKnown() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("type", "Radius");
        opts.put("radius", 30.0);
        opts.put("ignoredKey", "anything");
        Map<String, Object> resolved = SpatialGraphScripts.buildGraph(opts);

        assertThat(resolved).containsKey("type");
        assertThat(resolved.get("type")).isEqualTo("radius");
        assertThat(resolved.get("radius")).isEqualTo(30.0);
        // The canonical key set is what v1 commits to; unrecognised
        // keys must NOT leak through to the resolved map.
        assertThat(resolved).doesNotContainKey("ignoredKey");
    }

    @Test
    void buildGraphPopulatesAllCanonicalKeysFromDefaults() {
        Map<String, Object> resolved = SpatialGraphScripts.buildGraph();
        assertThat(resolved).containsKeys("type", "k", "radius", "maxEdge");
    }

    @Test
    void buildGraphIsNullSafe() {
        Map<String, Object> resolved = SpatialGraphScripts.buildGraph(null);
        assertThat(resolved).isNotEmpty();
    }

    @Test
    void buildGraphPreservesUserKWhenSet() {
        Map<String, Object> opts = new HashMap<>();
        opts.put("type", "knn");
        opts.put("k", 30);
        Map<String, Object> resolved = SpatialGraphScripts.buildGraph(opts);
        assertThat(resolved.get("k")).isEqualTo(30);
    }


    // ---- Independent areas ------------------------------------------------

    @Test
    void areasNormaliseToCanonicalLevelMaps() {
        var opts = new java.util.LinkedHashMap<String, Object>();
        opts.put("areas", java.util.List.of(
                java.util.Map.of("level", "TMA Cores"),
                java.util.Map.of("level", "annotations", "classes", java.util.List.of("Tissue"))));

        Map<String, Object> resolved = SpatialGraphScripts.buildGraph(opts);

        @SuppressWarnings("unchecked")
        var areas = (java.util.List<Map<String, Object>>) resolved.get("areas");
        assertThat(areas).hasSize(2);
        assertThat(areas.get(0)).containsEntry("level", "tma_cores");
        assertThat(areas.get(1)).containsEntry("level", "annotations");
        assertThat(areas.get(1).get("classes")).isEqualTo(java.util.List.of("Tissue"));
    }

    @Test
    void annotationClassesAliasIsAccepted() {
        var areas = SpatialGraphScripts.normaliseAreas(java.util.List.of(
                java.util.Map.of("level", "annotations",
                        "annotationClasses", java.util.List.of("Tissue", "Region"))));
        assertThat(areas.get(0).get("classes")).isEqualTo(java.util.List.of("Tissue", "Region"));
    }

    @Test
    void aMissingClassesListMeansAnyAnnotation() {
        var areas = SpatialGraphScripts.normaliseAreas(
                java.util.List.of(java.util.Map.of("level", "annotations")));
        assertThat(areas.get(0).get("classes")).isEqualTo(java.util.List.of());
    }

    /**
     * An unknown level THROWS rather than falling back. A script that asked to
     * split by cores and silently got one area would produce a plausible,
     * wrong result with nothing to indicate it -- unlike the other keys on
     * this facade, where a bad value only changes a graph parameter.
     */
    @Test
    void anUnknownLevelThrowsRatherThanFallingBack() {
        assertThatThrownBy(() -> SpatialGraphScripts.normaliseAreas(
                java.util.List.of(java.util.Map.of("level", "cores"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tma_cores");
    }

    @Test
    void listingImagesThrowsBecauseItIsImplicit() {
        assertThatThrownBy(() -> SpatialGraphScripts.normaliseAreas(
                java.util.List.of(java.util.Map.of("level", "images"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("implicit");
    }

    @Test
    void aMissingLevelKeyThrows() {
        assertThatThrownBy(() -> SpatialGraphScripts.normaliseAreas(
                java.util.List.of(java.util.Map.of("classes", java.util.List.of("Tissue")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level");
    }

    @Test
    void aNonListAreasValueThrows() {
        assertThatThrownBy(() -> SpatialGraphScripts.normaliseAreas("tma_cores"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list of maps");
    }

    @Test
    void areasAreAbsentByDefaultSoNothingIsPartitioned() {
        assertThat(SpatialGraphScripts.buildGraph()).doesNotContainKey("areas");
        assertThat(SpatialGraphScripts.normaliseAreas(null)).isEmpty();
    }
}
