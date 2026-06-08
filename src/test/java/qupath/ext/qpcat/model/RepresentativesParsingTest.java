package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClusteringResult} must parse the {@code representatives} JSON emitted
 * by run_clustering.py and return the medoid-first index lists per cluster,
 * for both the feature-space and embedding-space definitions.
 */
class RepresentativesParsingTest {

    private static ClusteringResult resultWith(String repsJson) {
        // 4 cells, 2 clusters; only the representatives JSON matters here.
        ClusteringResult r = new ClusteringResult(
                new int[]{0, 0, 1, 1}, 2, null, null, new String[]{"m"});
        r.setRepresentativesJson(repsJson);
        return r;
    }

    @Test
    void noRepresentativesReturnsEmpty() {
        ClusteringResult r = resultWith(null);
        assertThat(r.hasRepresentatives()).isFalse();
        assertThat(r.getRepresentativeIndices(0, false)).isEmpty();
        assertThat(r.hasEmbeddingRepresentatives()).isFalse();
    }

    @Test
    void parsesFeatureAndEmbeddingIndices() {
        String json = "{\"0\":{\"feature\":[1,0],\"embedding\":[0,1]},"
                + "\"1\":{\"feature\":[2,3],\"embedding\":[]}}";
        ClusteringResult r = resultWith(json);

        assertThat(r.hasRepresentatives()).isTrue();
        // Medoid first.
        assertThat(r.getRepresentativeIndices(0, false)).containsExactly(1, 0);
        assertThat(r.getRepresentativeIndices(0, true)).containsExactly(0, 1);
        assertThat(r.getRepresentativeIndices(1, false)).containsExactly(2, 3);
        assertThat(r.getRepresentativeIndices(1, true)).isEmpty();
    }

    @Test
    void embeddingRepresentativesDetectedOnlyWhenEmbeddingPresent() {
        String json = "{\"0\":{\"feature\":[0],\"embedding\":[0]}}";
        // No embedding matrix on the result -> embedding reps reported absent.
        ClusteringResult noEmb = resultWith(json);
        assertThat(noEmb.hasEmbeddingRepresentatives()).isFalse();

        // With an embedding matrix present, embedding reps are detected.
        ClusteringResult withEmb = new ClusteringResult(
                new int[]{0, 0, 1, 1}, 2,
                new double[][]{{0, 0}, {1, 1}, {2, 2}, {3, 3}}, null, new String[]{"m"});
        withEmb.setRepresentativesJson(json);
        assertThat(withEmb.hasEmbeddingRepresentatives()).isTrue();
    }

    @Test
    void unknownClusterReturnsEmpty() {
        String json = "{\"0\":{\"feature\":[0],\"embedding\":[]}}";
        ClusteringResult r = resultWith(json);
        assertThat(r.getRepresentativeIndices(5, false)).isEmpty();
    }
}
