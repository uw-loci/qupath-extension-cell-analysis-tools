package qupath.ext.qpcat.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.mcp.McpJsonRpc.Tool;
import qupath.ext.qpcat.mcp.McpJsonRpc.ToolResult;
import qupath.ext.qpcat.model.ClusterExplanation;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult.LlmExplanationsBundle;
import qupath.ext.qpcat.service.QpcatPaths;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three tools against a real temp project, up to the point where the
 * Python worker would be needed (which this JVM does not have, so those
 * calls must return the Appose edge-state text, not an exception).
 */
class McpExplainerToolsTest {

    @TempDir
    Path dir;

    private Project<BufferedImage> project;
    private Map<String, Tool> tools;

    @BeforeEach
    void setUp() throws Exception {
        project = Projects.createProject(dir.toFile(), BufferedImage.class);
        Path results = dir.resolve(QpcatPaths.CLUSTER_RESULTS);
        Files.createDirectories(results);
        writeResult(results, "plain", null);
        LlmExplanationsBundle existing = new LlmExplanationsBundle();
        existing.setProvider("CHATBOT");
        existing.setModel("claude.ai");
        existing.setTimestamp("2026-10-01T10:00:00Z");
        ClusterExplanation ex = new ClusterExplanation();
        ex.setClusterId(0);
        ex.setPhenotype("T cell");
        existing.setExplanations(List.of(ex));
        writeResult(results, "explained", existing);
        McpExplainerTools factory = new McpExplainerTools(() -> project);
        tools = new java.util.HashMap<>();
        for (Tool t : factory.tools()) tools.put(t.name(), t);
    }

    private static void writeResult(Path results, String name, LlmExplanationsBundle bundle) throws Exception {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setName(name);
        saved.setTimestamp("2026-10-12T12:00:00");
        saved.setNClusters(2);
        saved.setMarkerRankingsJson("{\"0\":[{\"name\":\"CD3\",\"score\":1.0}],\"1\":[{\"name\":\"PanCK\",\"score\":2.0}]}");
        saved.setLlmExplanations(bundle);
        Files.writeString(results.resolve(name + ".json"), new Gson().toJson(saved));
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void threeToolsWithTheDocumentedNamesAndRequiredArguments() {
        assertThat(tools.keySet()).containsExactlyInAnyOrder(
                "qpcat_list_results", "qpcat_explain_prompt", "qpcat_set_explanations");
        JsonArray required = tools.get("qpcat_set_explanations").inputSchema().getAsJsonArray("required");
        assertThat(required).extracting(e -> e.getAsString()).containsExactly("result", "response", "agent");
        assertThat(tools.get("qpcat_explain_prompt").inputSchema().getAsJsonArray("required"))
                .extracting(e -> e.getAsString()).containsExactly("result");
    }

    @Test
    void listResultsReportsHasExplanations() throws Exception {
        ToolResult r = tools.get("qpcat_list_results").call(new JsonObject());
        assertThat(r.isError()).isFalse();
        JsonArray rows = JsonParser.parseString(r.texts().get(0)).getAsJsonArray();
        assertThat(rows).hasSize(2);
        boolean sawExplained = false;
        for (var row : rows) {
            JsonObject o = row.getAsJsonObject();
            if (o.get("name").getAsString().equals("explained")) {
                sawExplained = true;
                assertThat(o.get("hasExplanations").getAsBoolean()).isTrue();
            } else {
                assertThat(o.get("hasExplanations").getAsBoolean()).isFalse();
            }
        }
        assertThat(sawExplained).isTrue();
    }

    @Test
    void unknownResultNamesAreRefusedWithoutEchoingAPath() throws Exception {
        for (String bad : List.of("leiden_v2", "../plain", "..%2Fplain", "plain/", ".plain", "plain\u0000")) {
            ToolResult r = tools.get("qpcat_explain_prompt").call(args("{\"result\":" + new Gson().toJson(bad) + "}"));
            assertThat(r.isError()).as(bad).isTrue();
            String text = r.texts().get(0);
            assertThat(text).startsWith("No saved clustering result named ");
            assertThat(text).contains("Call qpcat_list_results");
            assertThat(text).doesNotContain(dir.toString());
        }
    }

    @Test
    void existingExplanationsAreNotReplacedWithoutOverwrite() throws Exception {
        ToolResult r = tools.get("qpcat_set_explanations").call(args(
                "{\"result\":\"explained\",\"response\":\"[]\",\"agent\":\"test\"}"));
        assertThat(r.isError()).isTrue();
        assertThat(r.texts().get(0))
                .contains("already has 1 suggestions")
                .contains("claude.ai via CHATBOT at 2026-10-01T10:00:00Z")
                .contains("overwrite: true")
                .contains("Nothing has changed");
    }

    @Test
    void withoutThePythonWorkerTheToolsReturnTheApposeTextNotAnException() throws Exception {
        ToolResult prompt = tools.get("qpcat_explain_prompt").call(args("{\"result\":\"plain\"}"));
        assertThat(prompt.isError()).isTrue();
        assertThat(prompt.texts().get(0)).isEqualTo(McpExplainerTools.APPOSE_NOT_RUNNING);

        ToolResult set = tools.get("qpcat_set_explanations").call(args(
                "{\"result\":\"plain\",\"response\":\"[]\",\"agent\":\"test\"}"));
        assertThat(set.isError()).isTrue();
        assertThat(set.texts().get(0)).isEqualTo(McpExplainerTools.APPOSE_NOT_RUNNING);

        // overwrite: true on the explained result also reaches the gate, i.e. the flag is honoured
        ToolResult ow = tools.get("qpcat_set_explanations").call(args(
                "{\"result\":\"explained\",\"response\":\"[]\",\"agent\":\"test\",\"overwrite\":true}"));
        assertThat(ow.texts().get(0)).isEqualTo(McpExplainerTools.APPOSE_NOT_RUNNING);
    }

    @Test
    void argumentTypeMistakesAreIsErrorNotExceptions() throws Exception {
        assertThat(tools.get("qpcat_explain_prompt").call(args("{\"result\":42}")).isError()).isTrue();
        assertThat(tools.get("qpcat_explain_prompt").call(args("{\"result\":\"plain\",\"top_n\":0}"))
                .texts().get(0)).contains("top_n");
        assertThat(tools.get("qpcat_explain_prompt").call(args("{\"result\":\"plain\",\"cluster_ids\":\"0\"}"))
                .texts().get(0)).contains("cluster_ids");
        assertThat(tools.get("qpcat_set_explanations").call(args("{\"result\":\"plain\",\"response\":\"x\"}"))
                .texts().get(0)).contains("agent");
        assertThat(tools.get("qpcat_set_explanations").call(args("{\"result\":\"plain\",\"agent\":\"a\"}"))
                .texts().get(0)).contains("response");
    }

    @Test
    void noProjectIsAnErrorTextForTheAgent() throws Exception {
        McpExplainerTools none = new McpExplainerTools(() -> null);
        for (Tool t : none.tools()) {
            ToolResult r = t.call(args("{\"result\":\"plain\",\"response\":\"x\",\"agent\":\"a\"}"));
            assertThat(r.isError()).isTrue();
            assertThat(r.texts().get(0)).isEqualTo(McpExplainerTools.NO_PROJECT);
        }
    }

    @Test
    void agentLabelIsTruncatedAndStrippedOfControlCharacters() {
        assertThat(McpExplainerTools.sanitizeAgentLabel("Claude\u0007 Code\n(opus)"))
                .isEqualTo("Claude Code(opus)");
        assertThat(McpExplainerTools.sanitizeAgentLabel("x".repeat(100))).hasSize(60);
        assertThat(McpExplainerTools.sanitizeAgentLabel("\u0001\u0002")).isEqualTo("(unnamed agent)");
    }
}
