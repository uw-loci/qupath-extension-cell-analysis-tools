package qupath.ext.qpcat.scripting;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
