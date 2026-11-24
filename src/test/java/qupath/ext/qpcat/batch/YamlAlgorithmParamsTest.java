package qupath.ext.qpcat.batch;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import qupath.ext.qpcat.model.ClusteringConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A headless batch must run the algorithm and the parameters the YAML asked for.
 *
 * <p>Three ways it did not, all of which reported success:
 * <ul>
 *   <li>{@code type: minibatch_kmeans} -- the documented spelling, whitelisted by
 *       the validator, absent from the enum ids -- fell through to Leiden.</li>
 *   <li>BANKSY parameters were written as {@code banksy_lambda} / {@code banksy_k_geom};
 *       the script reads {@code lambda_param} / {@code k_geom}, so it used its own
 *       defaults.</li>
 *   <li>GMM's cluster count was written as {@code n_clusters}; the script reads
 *       {@code n_components}, so GMM always ran with 10.</li>
 * </ul>
 * Each is a value the user set being discarded and a default substituted with no
 * warning, which is the failure the tests below exist to prevent recurring.
 */
class YamlAlgorithmParamsTest {

    private static ClusteringConfig.Algorithm parse(String id) throws Exception {
        Method m = YamlBatchOrchestrator.class.getDeclaredMethod("parseAlgorithm", String.class);
        m.setAccessible(true);
        try {
            return (ClusteringConfig.Algorithm) m.invoke(null, id);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    @Test
    void everyEnumIdResolvesToItself() throws Exception {
        for (ClusteringConfig.Algorithm a : ClusteringConfig.Algorithm.values()) {
            assertThat(parse(a.getId())).isEqualTo(a);
        }
    }

    @Test
    void theDocumentedMinibatchSpellingRunsMinibatch() throws Exception {
        assertThat(parse("minibatch_kmeans"))
                .isEqualTo(ClusteringConfig.Algorithm.MINIBATCHKMEANS);
    }

    @Test
    void everySpellingTheValidatorAcceptsAlsoParses() throws Exception {
        // The validator's whitelist and this parser must agree. When they did
        // not, the user was told the file was valid and then given Leiden.
        // BatchYamlValidator.CLUSTERING_TYPES, minus "skip" -- that one means
        // "do not cluster" and is handled before the parser is reached.
        for (String id : new String[] {
                "leiden", "louvain", "kmeans", "minibatch_kmeans", "minibatchkmeans",
                "hdbscan", "agglomerative", "gmm", "banksy", "none", "existing"}) {
            assertThat(parse(id)).as("validator accepts '%s'", id).isNotNull();
        }
    }

    private static Object parsePrivate(String name, String id) throws Exception {
        Method m = YamlBatchOrchestrator.class.getDeclaredMethod(name, String.class);
        m.setAccessible(true);
        try {
            return m.invoke(null, id);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    @Test
    void everyNormalizationTheValidatorAcceptsAlsoParses() {
        for (String id : BatchYamlValidator.NORMALIZATIONS) {
            for (String spelling : new String[] {id, id.toUpperCase(java.util.Locale.ROOT)}) {
                assertThat(YamlBatchOrchestrator.parseNormalization(spelling))
                        .as("validator accepts '%s'", spelling).isNotNull();
            }
        }
    }

    @Test
    void log1pIsRefusedNotRunAsZscore() {
        assertThatThrownBy(() -> YamlBatchOrchestrator.parseNormalization("log1p"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String block : new String[] {
                "clustering: { type: leiden, normalization: log1p }\n",
                "clustering: { mode: reuse_saved, saved_result_name: r, normalization: LOG1P }\n"
                        + "phenotyping:\n  rules:\n    - name: T\n      require_markers: [A]\n"}) {
            BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                    "version: '1.0'\nscope: { projects: [/tmp/p] }\n" + block);
            assertThat(BatchYamlValidator.validate(po.getSchema()).getIssues())
                    .anyMatch(i -> i.getCode().equals("E005")
                            && i.getFieldPath().equals("clustering.normalization")
                            && i.getMessage().contains("not implemented"));
        }
    }

    @Test
    void phenotypingGetsTheCanonicalNormalizationId() {
        String[][] cases = {
                {"ZSCORE", "zscore"}, {"Percentile", "percentile"},
                {"percentile_99", "percentile"}, {"MinMax", "minmax"}, {"None", "none"}};
        for (String[] c : cases) {
            BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                    "version: '1.0'\nscope: { projects: [/tmp/p] }\n"
                    + "clustering: { type: leiden, normalization: " + c[0] + " }\n");
            assertThat(YamlBatchOrchestrator.phenotypingNormalization(po.getSchema()))
                    .as(c[0]).isEqualTo(c[1]);
        }
        BatchYamlParser.ParseOutcome none = BatchYamlParser.parseString(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n");
        assertThat(YamlBatchOrchestrator.phenotypingNormalization(none.getSchema()))
                .isEqualTo("zscore");
    }

    @Test
    void everyEmbeddingTheValidatorAcceptsAlsoParses() throws Exception {
        for (String id : new String[] {"umap", "pca", "tsne", "none"}) {
            assertThat(parsePrivate("parseEmbedding", id))
                    .as("validator accepts '%s'", id).isNotNull();
        }
    }

    @Test
    void anUnknownNormalizationFailsLoudlyRatherThanBecomingZscore() {
        assertThatThrownBy(() -> parsePrivate("parseNormalization", "arcsinh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arcsinh");
    }

    @Test
    void anUnknownEmbeddingFailsLoudlyRatherThanBecomingUmap() {
        assertThatThrownBy(() -> parsePrivate("parseEmbedding", "phate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phate");
    }

    @Test
    void anUnknownTypeFailsLoudlyRatherThanBecomingLeiden() {
        assertThatThrownBy(() -> parse("kmeanz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kmeanz")
                .hasMessageContaining("Accepted");
    }
}
