package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ClusteringResultManager#embeddingPrefixFromConfigJson(String)}.
 * <p>
 * Results saved before the prefix was recorded on the result itself still have their run
 * config sidecar beside them, and it names the embedding exactly. Recovering it there is
 * what lets an existing project open the 3D view on its own embedding with no re-run.
 */
class ClusteringResultManagerSidecarTest {

    @Test
    void aCustomEmbeddingNameIsRecovered() {
        String json = """
                {"algorithm":"LEIDEN","embeddingMethod":"UMAP",
                 "embeddingParams":{"n_components":3,"name":"UMAP_Demo"}}
                """;
        assertThat(ClusteringResultManager.embeddingPrefixFromConfigJson(json)).isEqualTo("UMAP_Demo");
    }

    @Test
    void withNoCustomNameTheMethodDefaultIsUsed() {
        String json = """
                {"embeddingMethod":"UMAP","embeddingParams":{"n_components":2}}
                """;
        assertThat(ClusteringResultManager.embeddingPrefixFromConfigJson(json)).isEqualTo("UMAP");
    }

    @Test
    void anEmbeddinglessRunYieldsNull() {
        assertThat(ClusteringResultManager.embeddingPrefixFromConfigJson("{\"embeddingMethod\":\"NONE\"}"))
                .isNull();
        assertThat(ClusteringResultManager.embeddingPrefixFromConfigJson("{\"algorithm\":\"LEIDEN\"}")).isNull();
    }

    @Test
    void malformedJsonIsNullRatherThanAnException() {
        assertThat(ClusteringResultManager.embeddingPrefixFromConfigJson("not json at all")).isNull();
        assertThat(ClusteringResultManager.embeddingPrefixFromConfigJson("")).isNull();
    }
}
