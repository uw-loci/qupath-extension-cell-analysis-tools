package qupath.ext.qpcat.service;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Load Config from file..." can only be pointed at a config. A project's
 * cluster_results/ folder holds two json files per run whose names differ by one
 * suffix, and Gson deserializes either into a ClusteringConfig without
 * complaining -- so picking the result silently loaded a default config.
 */
class ConfigFileRecognitionTest {

    /** Mirrors loadConfigFromFile: anything that is not a JSON object arrives as null. */
    private static JsonObject parse(String json) {
        com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(json);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    @Test
    void acceptsASavedConfig() {
        String json = "{\"algorithm\":\"LEIDEN\",\"normalization\":\"ZSCORE\","
                + "\"selectedMeasurements\":[\"CD8: Mean\"],\"algorithmParams\":{\"resolution\":1.0}}";
        assertNull(ClusteringConfigManager.describeIfNotAConfig("run_config.json", parse(json)));
    }

    @Test
    void acceptsAConfigCarryingOnlySomeOfTheKeys() {
        // A config written by an older version may lack embeddingParams etc.
        assertNull(ClusteringConfigManager.describeIfNotAConfig(
                "old_config.json", parse("{\"algorithm\":\"KMEANS\"}")));
    }

    @Test
    void rejectsASavedResultAndNamesTheConfigToPickInstead() {
        // Note 'algorithm' and 'normalization' appear in BOTH shapes, so the
        // result-only keys are what has to decide it.
        String json = "{\"name\":\"auto_kmeans\",\"algorithm\":\"kmeans\","
                + "\"normalization\":\"zscore\",\"nClusters\":8,\"nCells\":4000,"
                + "\"clusterLabels\":[0,1,2]}";
        String msg = ClusteringConfigManager.describeIfNotAConfig("auto_kmeans.json", parse(json));
        assertNotNull(msg);
        assertTrue(msg.contains("auto_kmeans_config.json"), msg);
        assertTrue(msg.contains("RESULT"), msg);
    }

    @Test
    void rejectsAResultEvenWhenItLacksTheConfigKeys() {
        String msg = ClusteringConfigManager.describeIfNotAConfig(
                "r.json", parse("{\"clusterStats\":[[1.0]]}"));
        assertNotNull(msg);
        assertTrue(msg.contains("RESULT"), msg);
    }

    @Test
    void rejectsAnUnrelatedJsonFile() {
        String msg = ClusteringConfigManager.describeIfNotAConfig(
                "project.qpproj", parse("{\"images\":[]}"));
        assertNotNull(msg);
        assertTrue(msg.contains("not a QP-CAT clustering config"), msg);
    }

    @Test
    void rejectsAnythingThatIsNotAJsonObject() {
        assertNotNull(ClusteringConfigManager.describeIfNotAConfig("empty.json", parse("")));
        assertNotNull(ClusteringConfigManager.describeIfNotAConfig("null.json", parse("null")));
        assertNotNull(ClusteringConfigManager.describeIfNotAConfig("list.json", parse("[1,2]")));
    }

    @Test
    void sidecarSuffixIsTheOneUsedByTheFilter() {
        assertTrue(ClusteringResultManager.CONFIG_SIDECAR_SUFFIX.equals("_config.json"),
                ClusteringResultManager.CONFIG_SIDECAR_SUFFIX);
    }
}
