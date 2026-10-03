package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing one k-sweep, and the two questions the dialog asks of it.
 *
 * <p>The sweep's own correctness is pinned on the Python side
 * ({@code python_tests/test_choose_k.py}). What matters here is that a null in
 * the JSON -- which is what the script emits for a statistic it could not
 * compute -- survives as "no value" rather than becoming a 0 that would read as
 * a real measurement, and that
 * {@link ChooseKResult#gapSaysNoStructure()} is asked separately from the other
 * suggestions. That last one carries the finding: measured over five seeds on
 * data with no clusters in it, the elbow said 3 or 4 and the silhouette 6 to 8
 * every time, while the gap said k = 1 in all five runs.
 */
class ChooseKResultTest {

    private static final String AGREEING = """
            {"ks":[1,2,3,4],
             "inertia":[100.0,60.0,30.0,28.0],
             "silhouette":[null,0.60,0.89,0.55],
             "gap":[-0.4,-0.3,2.3,2.0],
             "gap_std_error":[0.02,0.02,0.03,0.03],
             "suggested":{"elbow":3,"silhouette":3,"gap":3},
             "meta":{"n_cells":600,"n_features":20,"silhouette_cells":600,
                     "silhouette_subsampled":false,"gap_n_references":10,
                     "seed":42,"k_min":1,"k_max":4},
             "warnings":[]}
            """;

    /** The measured noise case: three statistics, three answers, gap says 1. */
    private static final String NOISE = """
            {"ks":[1,2,3,4,5,6,7,8],
             "inertia":[2000.0,1800.0,1700.0,1640.0,1600.0,1570.0,1550.0,1535.0],
             "silhouette":[null,0.05,0.05,0.06,0.06,0.07,0.07,0.08],
             "gap":[0.30,0.22,0.18,0.15,0.13,0.11,0.10,0.09],
             "gap_std_error":[0.02,0.02,0.02,0.02,0.02,0.02,0.02,0.02],
             "suggested":{"elbow":4,"silhouette":8,"gap":1},
             "meta":{"n_cells":400,"n_features":5,"silhouette_cells":400,
                     "silhouette_subsampled":false,"gap_n_references":10,
                     "seed":0,"k_min":1,"k_max":8},
             "warnings":["The silhouette is computed on a subsample."]}
            """;

    @Test
    void aSweepParsesItsCurvesAndMetadata() {
        ChooseKResult r = ChooseKResult.fromJson(AGREEING);
        assertThat(r).isNotNull();
        assertThat(r.getKs()).containsExactly(1, 2, 3, 4);
        assertThat(r.getInertia()).hasSize(4);
        assertThat(r.getMeta().getNCells()).isEqualTo(600);
        assertThat(r.getMeta().getNFeatures()).isEqualTo(20);
        assertThat(r.getMeta().getGapNReferences()).isEqualTo(10);
        assertThat(r.getMeta().isSilhouetteSubsampled()).isFalse();
        assertThat(r.getMeta().getSeed()).isEqualTo(42);
    }

    @Test
    void anAbsentStatisticStaysAbsentRatherThanBecomingZero() {
        // The silhouette at k = 1 is undefined -- there is no other cluster to
        // compare against. A 0 here would read as "measured, and terrible".
        ChooseKResult r = ChooseKResult.fromJson(AGREEING);
        assertThat(r.getSilhouette().get(0)).isNull();
        assertThat(r.getSilhouette().get(1)).isEqualTo(0.60);
    }

    @Test
    void whenAllThreeAgreeThatIsReportedAsAgreement() {
        ChooseKResult r = ChooseKResult.fromJson(AGREEING);
        assertThat(r.distinctSuggestions()).containsExactly(3);
        assertThat(r.suggestionsDisagree()).isFalse();
        assertThat(r.gapSaysNoStructure()).isFalse();
    }

    @Test
    void theNoiseCaseIsDetectedAsNoStructureNotMerelyAsDisagreement() {
        // Both things are true of this sweep, and they are not the same warning:
        // "they disagree" means weigh them, "the gap says k = 1" means the
        // matrix may have nothing in it to divide. The dialog must be able to
        // tell them apart, so they are separate predicates.
        ChooseKResult r = ChooseKResult.fromJson(NOISE);
        assertThat(r.suggestionsDisagree()).isTrue();
        assertThat(r.gapSaysNoStructure()).isTrue();
        assertThat(r.distinctSuggestions()).containsExactly(1, 4, 8);
    }

    @Test
    void aMissingSuggestionIsNotOfferedAsAChoice() {
        // The gap reports no suggestion when its curve is still rising at k_max:
        // the range was too small. Offering a "Use k = null" button, or treating
        // it as k_max, would turn a boundary artifact into a recommendation.
        String json = AGREEING.replace("\"gap\":3", "\"gap\":null");
        ChooseKResult r = ChooseKResult.fromJson(json);
        assertThat(r.getSuggested().getGap()).isNull();
        assertThat(r.distinctSuggestions()).containsExactly(3);
        assertThat(r.hasAnySuggestion()).isTrue();
    }

    @Test
    void aSweepWithNoSuggestionAtAllSaysSo() {
        String json = AGREEING
                .replace("\"elbow\":3", "\"elbow\":null")
                .replace("\"silhouette\":3", "\"silhouette\":null")
                .replace("\"gap\":3", "\"gap\":null");
        ChooseKResult r = ChooseKResult.fromJson(json);
        assertThat(r.hasAnySuggestion()).isFalse();
        assertThat(r.distinctSuggestions()).isEmpty();
        assertThat(r.suggestionsDisagree()).isFalse();
    }

    @Test
    void warningsFromTheSweepSurvive() {
        assertThat(ChooseKResult.fromJson(NOISE).getWarnings())
                .anySatisfy(w -> assertThat(w).contains("subsample"));
        assertThat(ChooseKResult.fromJson(AGREEING).getWarnings()).isEmpty();
    }

    @Test
    void unusableJsonParsesToNullRatherThanThrowing() {
        // The script logs and continues on a failure, so the Java side can be
        // handed an empty payload and must not take the dialog down with it.
        assertThat(ChooseKResult.fromJson(null)).isNull();
        assertThat(ChooseKResult.fromJson("")).isNull();
        assertThat(ChooseKResult.fromJson("   ")).isNull();
        assertThat(ChooseKResult.fromJson("{}")).isNull();
        assertThat(ChooseKResult.fromJson("{\"ks\":[]}")).isNull();
    }

    @Test
    void accessorsNeverReturnNullForAMinimalPayload() {
        ChooseKResult r = ChooseKResult.fromJson("{\"ks\":[2,3]}");
        assertThat(r).isNotNull();
        assertThat(r.getInertia()).isEmpty();
        assertThat(r.getSilhouette()).isEmpty();
        assertThat(r.getGap()).isEmpty();
        assertThat(r.getGapStdError()).isEmpty();
        assertThat(r.getWarnings()).isEmpty();
        assertThat(r.getSuggested()).isNotNull();
        assertThat(r.getMeta()).isNotNull();
        assertThat(r.hasAnySuggestion()).isFalse();
    }

    @Test
    void theSuggestionsAreReportedAscendingAndDeduplicated() {
        String json = NOISE.replace("\"silhouette\":8", "\"silhouette\":4");
        ChooseKResult r = ChooseKResult.fromJson(json);
        // elbow 4, silhouette 4, gap 1 -> one button for 1 and one for 4.
        assertThat(r.distinctSuggestions()).containsExactly(1, 4);
    }
}
