package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.SavedClusteringResult.LlmExplanationsBundle;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gson round trip of {@link LlmExplanationsBundle} through
 * {@link SavedClusteringResult}, with the same Gson settings the result
 * manager writes with. The fields were unreferenced until the MCP tools
 * started persisting them, so this had never run.
 */
class LlmExplanationsBundleRoundTripTest {

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .serializeSpecialFloatingPointValues()
            .create();

    private static LlmExplanationsBundle sample() {
        ClusterExplanation a = new ClusterExplanation();
        a.setClusterId(0);
        a.setPhenotype("CD8 T cell");
        a.setConfidence(ClusterExplanation.Confidence.fromRaw("high"));
        a.setRationale("CD3 and CD8 lead.");
        a.setSupportingMarkers(List.of("CD3", "CD8"));
        ClusterExplanation refused = new ClusterExplanation();
        refused.setClusterId(1);
        refused.setPhenotype(null);
        refused.setConfidence(null);
        refused.setRationale("insufficient signal");
        refused.setSupportingMarkers(List.of());

        LlmExplanationsBundle b = new LlmExplanationsBundle();
        b.setProvider("MCP");
        b.setModel("Claude Code (test)");
        b.setPromptTemplate("cluster_phenotype_v2");
        b.setPromptHash("ab".repeat(32));
        b.setTimestamp("2026-10-13T09:00:00.123456-05:00");
        b.setPromptText("You are a computational pathology assistant ... \"quoted\" \\ back");
        b.setResponseRaw("[{\"cluster_id\":0}]");
        b.setInputTokens(-1);
        b.setOutputTokens(-1);
        b.setExplanations(List.of(a, refused));
        return b;
    }

    @Test
    void bundleSurvivesSerialiseAndDeserialiseInsideASavedResult() {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setName("r");
        saved.setNClusters(2);
        saved.setLlmExplanations(sample());

        String json = gson.toJson(saved);
        assertThat(json).contains("\"llmExplanations\"");
        SavedClusteringResult restored = gson.fromJson(json, SavedClusteringResult.class);

        assertThat(restored.hasLlmExplanations()).isTrue();
        LlmExplanationsBundle b = restored.getLlmExplanations();
        assertThat(b.getProvider()).isEqualTo("MCP");
        assertThat(b.getModel()).isEqualTo("Claude Code (test)");
        assertThat(b.getPromptTemplate()).isEqualTo("cluster_phenotype_v2");
        assertThat(b.getPromptHash()).isEqualTo("ab".repeat(32));
        assertThat(b.getTimestamp()).isEqualTo("2026-10-13T09:00:00.123456-05:00");
        assertThat(b.getPromptText()).isEqualTo(sample().getPromptText());
        assertThat(b.getResponseRaw()).isEqualTo("[{\"cluster_id\":0}]");
        assertThat(b.getInputTokens()).isEqualTo(-1);
        assertThat(b.getOutputTokens()).isEqualTo(-1);
        assertThat(b.getExplanations()).hasSize(2);
        ClusterExplanation a = b.getExplanations().get(0);
        assertThat(a.getClusterId()).isEqualTo(0);
        assertThat(a.getPhenotype()).isEqualTo("CD8 T cell");
        assertThat(a.getConfidence()).isEqualTo(ClusterExplanation.Confidence.HIGH);
        assertThat(a.getSupportingMarkers()).containsExactly("CD3", "CD8");
        ClusterExplanation refused = b.getExplanations().get(1);
        assertThat(refused.getPhenotype()).isNull();
        assertThat(refused.getConfidence()).isNull();
        assertThat(refused.getRationale()).isEqualTo("insufficient signal");

        // Byte-for-byte stable across a second cycle.
        assertThat(gson.toJson(restored)).isEqualTo(json);
    }

    @Test
    void anEmptyBundleDoesNotCountAsExplanations() {
        SavedClusteringResult saved = new SavedClusteringResult();
        LlmExplanationsBundle empty = new LlmExplanationsBundle();
        empty.setProvider("MCP");
        empty.setExplanations(List.of());
        saved.setLlmExplanations(empty);
        SavedClusteringResult restored = gson.fromJson(gson.toJson(saved), SavedClusteringResult.class);
        assertThat(restored.getLlmExplanations()).isNotNull();
        assertThat(restored.hasLlmExplanations()).isFalse();
    }

    @Test
    void olderSavesWithoutTheFieldReadBackAsNull() {
        SavedClusteringResult restored = gson.fromJson("{\"name\":\"old\",\"nClusters\":3}",
                SavedClusteringResult.class);
        assertThat(restored.getLlmExplanations()).isNull();
        assertThat(restored.hasLlmExplanations()).isFalse();
    }
}
