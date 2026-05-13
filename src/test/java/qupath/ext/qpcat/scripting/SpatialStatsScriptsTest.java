package qupath.ext.qpcat.scripting;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 invariants for the {@link SpatialStatsScripts} option-map
 * facade. Pins the method-name and option-key contract documented in
 * SCRIPTING.md so a future rename trips this test rather than silently
 * breaking scripted workflows.
 */
class SpatialStatsScriptsTest {

    @Test
    void ripleyEchoesCanonicalKeysAndDefaults() {
        Map<String, Object> graph = SpatialGraphScripts.buildGraph();
        Map<String, Object> out = SpatialStatsScripts.ripley(graph, null);

        assertThat(out.get("method")).isEqualTo("ripley");
        assertThat(out).containsKeys("maxRadius", "nSteps", "nPermutations");
        assertThat(out.get("nPermutations")).isEqualTo(SpatialStatsScripts.PERMUTATIONS_ADAPTIVE);
        assertThat(out.get("graph")).isSameAs(graph);
    }

    @Test
    void ripleyAcceptsExplicitOverrides() {
        Map<String, Object> opts = new HashMap<>();
        opts.put("maxRadius", 50.0);
        opts.put("nSteps", 25);
        opts.put("nPermutations", 100);
        Map<String, Object> out = SpatialStatsScripts.ripley(null, opts);
        assertThat(out.get("maxRadius")).isEqualTo(50.0);
        assertThat(out.get("nSteps")).isEqualTo(25);
        assertThat(out.get("nPermutations")).isEqualTo(100);
    }

    @Test
    void ripleyIgnoresUnknownKeys() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("clobber", true);
        opts.put("nSteps", 10);
        Map<String, Object> out = SpatialStatsScripts.ripley(null, opts);
        assertThat(out).doesNotContainKey("clobber");
        assertThat(out.get("nSteps")).isEqualTo(10);
    }

    @Test
    void gearyAcceptsMeasurementList() {
        Map<String, Object> opts = new HashMap<>();
        opts.put("measurements", List.of("CD3", "CD8"));
        Map<String, Object> out = SpatialStatsScripts.gearyC(null, opts);
        assertThat(out.get("method")).isEqualTo("geary");
        @SuppressWarnings("unchecked")
        List<String> markers = (List<String>) out.get("measurements");
        assertThat(markers).containsExactly("CD3", "CD8");
    }

    @Test
    void moranSharesGearySignatureButFlipsMethod() {
        Map<String, Object> out = SpatialStatsScripts.moranI(null, null);
        assertThat(out.get("method")).isEqualTo("moran");
    }

    @Test
    void coOccurrenceNormalisesMode() {
        Map<String, Object> pairwise = SpatialStatsScripts.coOccurrence(null,
                Map.of("mode", "PAIRWISE"));
        assertThat(pairwise.get("mode")).isEqualTo("pairwise");

        Map<String, Object> oneVsRest1 = SpatialStatsScripts.coOccurrence(null,
                Map.of("mode", "oneVsRest"));
        assertThat(oneVsRest1.get("mode")).isEqualTo("oneVsRest");

        Map<String, Object> oneVsRest2 = SpatialStatsScripts.coOccurrence(null,
                Map.of("mode", "one_vs_rest"));
        assertThat(oneVsRest2.get("mode")).isEqualTo("oneVsRest");

        Map<String, Object> unknown = SpatialStatsScripts.coOccurrence(null,
                Map.of("mode", "matrix"));
        assertThat(unknown.get("mode")).isEqualTo("pairwise");
    }

    @Test
    void coOccurrenceFillsAllCanonicalKeys() {
        Map<String, Object> out = SpatialStatsScripts.coOccurrence(null, null);
        assertThat(out).containsKeys("method", "mode", "minRadius", "maxRadius", "nIntervals");
    }

    @Test
    void neighborhoodEnrichmentTakesNoOptions() {
        Map<String, Object> out = SpatialStatsScripts.neighborhoodEnrichment(null, null);
        assertThat(out.get("method")).isEqualTo("neighborhood_enrichment");
    }

    @Test
    void permutationsAdaptiveSentinelStableAtMinusOne() {
        // The sentinel is part of the public stability surface; if it
        // changes, every cached recorded-workflow script breaks.
        assertThat(SpatialStatsScripts.PERMUTATIONS_ADAPTIVE).isEqualTo(-1);
    }

    @Test
    void persistPlotsOptionKeyStable() {
        // Phase 5 cross-feature contract with Multi-Figure Batch Export:
        // the option key string is part of the public API surface.
        assertThat(SpatialStatsScripts.OPTION_PERSIST_PLOTS).isEqualTo("persistPlots");
    }

    @Test
    void ripleyRecognisesPersistPlotsOption() {
        Map<String, Object> opts = new HashMap<>();
        opts.put(SpatialStatsScripts.OPTION_PERSIST_PLOTS, false);
        Map<String, Object> out = SpatialStatsScripts.ripley(null, opts);
        assertThat(out).containsEntry(SpatialStatsScripts.OPTION_PERSIST_PLOTS, false);
    }

    @Test
    void gearyRecognisesPersistPlotsOption() {
        Map<String, Object> opts = new HashMap<>();
        opts.put(SpatialStatsScripts.OPTION_PERSIST_PLOTS, true);
        Map<String, Object> out = SpatialStatsScripts.gearyC(null, opts);
        assertThat(out).containsEntry(SpatialStatsScripts.OPTION_PERSIST_PLOTS, true);
    }

    @Test
    void coOccurrenceRecognisesPersistPlotsOption() {
        Map<String, Object> opts = new HashMap<>();
        opts.put(SpatialStatsScripts.OPTION_PERSIST_PLOTS, "yes");  // string coercion
        Map<String, Object> out = SpatialStatsScripts.coOccurrence(null, opts);
        assertThat(out).containsEntry(SpatialStatsScripts.OPTION_PERSIST_PLOTS, true);
    }
}
