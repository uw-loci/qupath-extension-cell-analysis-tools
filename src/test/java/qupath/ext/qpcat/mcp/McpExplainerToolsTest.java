package qupath.ext.qpcat.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.mcp.McpJsonRpc.CallContext;
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

    /** A well-formed sha256 hex; the Appose gate is reached before it is compared. */
    private static final String HASH = "a".repeat(64);

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
        assertThat(required).extracting(e -> e.getAsString()).containsExactly("result", "response", "agent", "prompt_hash");
        assertThat(tools.get("qpcat_explain_prompt").inputSchema().getAsJsonArray("required"))
                .extracting(e -> e.getAsString()).containsExactly("result");
    }

    @Test
    void listResultsReportsHasExplanations() throws Exception {
        ToolResult r = tools.get("qpcat_list_results").call(new JsonObject(), CallContext.NONE);
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
            ToolResult r = tools.get("qpcat_explain_prompt").call(args("{\"result\":" + new Gson().toJson(bad) + "}"), CallContext.NONE);
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
                "{\"result\":\"explained\",\"response\":\"[]\",\"agent\":\"test\",\"prompt_hash\":\"" + HASH + "\"}"), CallContext.NONE);
        assertThat(r.isError()).isTrue();
        assertThat(r.texts().get(0))
                .contains("already has 1 suggestions")
                .contains("claude.ai via CHATBOT at 2026-10-01T10:00:00Z")
                .contains("overwrite: true")
                .contains("Nothing has changed");
    }

    @Test
    void withoutThePythonWorkerTheToolsReturnTheApposeTextNotAnException() throws Exception {
        ToolResult prompt = tools.get("qpcat_explain_prompt").call(args("{\"result\":\"plain\"}"), CallContext.NONE);
        assertThat(prompt.isError()).isTrue();
        assertThat(prompt.texts().get(0)).isEqualTo(McpExplainerTools.APPOSE_NOT_RUNNING);

        ToolResult set = tools.get("qpcat_set_explanations").call(args(
                "{\"result\":\"plain\",\"response\":\"[]\",\"agent\":\"test\",\"prompt_hash\":\"" + HASH + "\"}"), CallContext.NONE);
        assertThat(set.isError()).isTrue();
        assertThat(set.texts().get(0)).isEqualTo(McpExplainerTools.APPOSE_NOT_RUNNING);

        // overwrite: true on the explained result also reaches the gate, i.e. the flag is honoured
        ToolResult ow = tools.get("qpcat_set_explanations").call(args(
                "{\"result\":\"explained\",\"response\":\"[]\",\"agent\":\"test\",\"prompt_hash\":\"" + HASH + "\",\"overwrite\":true}"), CallContext.NONE);
        assertThat(ow.texts().get(0)).isEqualTo(McpExplainerTools.APPOSE_NOT_RUNNING);
    }

    @Test
    void argumentTypeMistakesAreIsErrorNotExceptions() throws Exception {
        assertThat(tools.get("qpcat_explain_prompt").call(args("{\"result\":42}"), CallContext.NONE).isError()).isTrue();
        assertThat(tools.get("qpcat_explain_prompt").call(args("{\"result\":\"plain\",\"top_n\":0}"), CallContext.NONE)
                .texts().get(0)).contains("top_n");
        assertThat(tools.get("qpcat_explain_prompt").call(args("{\"result\":\"plain\",\"cluster_ids\":\"0\"}"), CallContext.NONE)
                .texts().get(0)).contains("cluster_ids");
        assertThat(tools.get("qpcat_set_explanations").call(args("{\"result\":\"plain\",\"response\":\"x\"}"), CallContext.NONE)
                .texts().get(0)).contains("agent");
        assertThat(tools.get("qpcat_set_explanations").call(args("{\"result\":\"plain\",\"agent\":\"a\",\"prompt_hash\":\"" + HASH + "\"}"), CallContext.NONE)
                .texts().get(0)).contains("response");
    }

    @Test
    void noProjectIsAnErrorTextForTheAgent() throws Exception {
        McpExplainerTools none = new McpExplainerTools(() -> null);
        for (Tool t : none.tools()) {
            ToolResult r = t.call(argsFor(t), CallContext.NONE);
            assertThat(r.isError()).isTrue();
            assertThat(r.texts().get(0)).isEqualTo(McpExplainerTools.NO_PROJECT);
        }
    }

    /** Well-formed arguments for each tool, so only the project can be at fault. */
    private static JsonObject argsFor(Tool t) {
        switch (t.name()) {
            case "qpcat_list_results": return new JsonObject();
            case "qpcat_explain_prompt": return args("{\"result\":\"plain\"}");
            default: return args("{\"result\":\"plain\",\"response\":\"x\",\"agent\":\"a\",\"prompt_hash\":\"" + HASH + "\"}");
        }
    }

    @Test
    void agentLabelIsTruncatedAndStrippedOfControlCharacters() {
        assertThat(McpExplainerTools.sanitizeAgentLabel("Claude\u0007 Code\n(opus)"))
                .isEqualTo("Claude Code(opus)");
        assertThat(McpExplainerTools.sanitizeAgentLabel("x".repeat(100))).hasSize(60);
        assertThat(McpExplainerTools.sanitizeAgentLabel("\u0001\u0002")).isEqualTo("(unnamed agent)");
    }

    // ---- Phase 5 fixes ----

    @Test
    void unknownArgumentNamesAreRefusedNamingTheAcceptedOnes() throws Exception {
        ToolResult r = tools.get("qpcat_explain_prompt").call(
                args("{\"result\":\"plain\",\"topN\":3,\"clusterIds\":[0]}"), CallContext.NONE);
        assertThat(r.isError()).isTrue();
        assertThat(r.texts().get(0)).contains("[clusterIds, topN]").contains("cluster_ids").contains("top_n");
        ToolResult list = tools.get("qpcat_list_results").call(args("{\"bogus\":1}"), CallContext.NONE);
        assertThat(list.isError()).isTrue();
        assertThat(list.texts().get(0)).contains("takes no arguments");
        // validate() gives the same refusals without running the tool
        assertThat(tools.get("qpcat_explain_prompt").validate(args("{\"result\":\"plain\",\"topN\":3}")).texts().get(0))
                .contains("[topN]");
        assertThat(tools.get("qpcat_explain_prompt").validate(args("{\"result\":\"plain\"}"))).isNull();
    }

    @Test
    void overwriteMustBeAJsonBooleanNotAStringOrNumber() throws Exception {
        for (String form : List.of("\"true\"", "1", "\"yes\"")) {
            ToolResult r = tools.get("qpcat_set_explanations").call(args(
                    "{\"result\":\"explained\",\"response\":\"[]\",\"agent\":\"t\",\"prompt_hash\":\"" + HASH + "\",\"overwrite\":" + form + "}"),
                    CallContext.NONE);
            assertThat(r.isError()).as(form).isTrue();
            assertThat(r.texts().get(0)).as(form).contains("overwrite must be the JSON boolean")
                    .doesNotContain("already has");
        }
    }

    @Test
    void integerArgumentsGetTypeSpecificRefusals() throws Exception {
        Tool explain = tools.get("qpcat_explain_prompt");
        assertThat(explain.call(args("{\"result\":\"plain\",\"top_n\":\"5\"}"), CallContext.NONE).texts().get(0))
                .contains("top_n must be a JSON integer, not the string \"5\"");
        assertThat(explain.call(args("{\"result\":\"plain\",\"top_n\":5.7}"), CallContext.NONE).texts().get(0))
                .contains("whole number");
        assertThat(explain.call(args("{\"result\":\"plain\",\"cluster_ids\":[1.5]}"), CallContext.NONE).texts().get(0))
                .contains("whole numbers only");
        assertThat(explain.call(args("{\"result\":\"plain\",\"cluster_ids\":[\"0\"]}"), CallContext.NONE).texts().get(0))
                .contains("integers only");
        // ids the result does not have are refused Java-side, listing the real ones
        ToolResult missing = explain.call(args("{\"result\":\"plain\",\"cluster_ids\":[0,999]}"), CallContext.NONE);
        assertThat(missing.isError()).isTrue();
        assertThat(missing.texts().get(0)).contains("[999]").contains("[0, 1]");
    }

    @Test
    void promptHashMustBeASha256HexStringWhenGiven() throws Exception {
        ToolResult bad = tools.get("qpcat_set_explanations").call(args(
                "{\"result\":\"plain\",\"response\":\"[]\",\"agent\":\"t\",\"prompt_hash\":\"abc\"}"), CallContext.NONE);
        assertThat(bad.isError()).isTrue();
        assertThat(bad.texts().get(0)).contains("prompt_hash must be the 64-character sha256");
        // a well-formed hash is accepted up to the Appose gate (no Python in this JVM)
        ToolResult ok = tools.get("qpcat_set_explanations").call(args(
                "{\"result\":\"plain\",\"response\":\"[]\",\"agent\":\"t\",\"prompt_hash\":\"" + "a".repeat(64)
                        + "\",\"top_n\":3,\"cluster_ids\":[0]}"), CallContext.NONE);
        assertThat(ok.texts().get(0)).isEqualTo(McpExplainerTools.APPOSE_NOT_RUNNING);
        JsonObject props = tools.get("qpcat_set_explanations").inputSchema().getAsJsonObject("properties");
        assertThat(props.keySet()).contains("prompt_hash", "top_n", "cluster_ids", "overwrite");
        // and it is required: a call without it is refused by name, before anything is read
        ToolResult missing = tools.get("qpcat_set_explanations").call(args(
                "{\"result\":\"plain\",\"response\":\"[]\",\"agent\":\"t\"}"), CallContext.NONE);
        assertThat(missing.isError()).isTrue();
        assertThat(missing.texts().get(0)).startsWith("The \"prompt_hash\" argument is required");
        assertThat(tools.get("qpcat_set_explanations").validate(args(
                "{\"result\":\"plain\",\"response\":\"[]\",\"agent\":\"t\"}")).texts().get(0))
                .contains("prompt_hash");
    }

    @Test
    void listResultsReportsACoarseScopeNeverTheImageName() throws Exception {
        Path results = dir.resolve(QpcatPaths.CLUSTER_RESULTS);
        SavedClusteringResult single = new SavedClusteringResult();
        single.setName("single");
        single.setTimestamp("2026-10-12T13:00:00");
        single.setScopeLabel("case_ACC-2026-0042_HE.svs");
        Files.writeString(results.resolve("single.json"), new Gson().toJson(single));
        SavedClusteringResult multi = new SavedClusteringResult();
        multi.setName("multi");
        multi.setTimestamp("2026-10-12T14:00:00");
        multi.setScopeLabel("8 project images");
        Files.writeString(results.resolve("multi.json"), new Gson().toJson(multi));

        ToolResult r = tools.get("qpcat_list_results").call(new JsonObject(), CallContext.NONE);
        String text = r.texts().get(0);
        assertThat(text).doesNotContain("ACC-2026-0042").doesNotContain(".svs").doesNotContain(dir.toString());
        for (var row : JsonParser.parseString(text).getAsJsonArray()) {
            JsonObject o = row.getAsJsonObject();
            String scope = o.get("scope").getAsString();
            switch (o.get("name").getAsString()) {
                case "single" -> assertThat(scope).isEqualTo("1 image");
                case "multi" -> assertThat(scope).isEqualTo("8 images");
                default -> assertThat(scope).isEqualTo("unknown scope");
            }
        }
        assertThat(McpExplainerTools.coarseScope("3 project image(s), existing classifications")).isEqualTo("3 images");
        assertThat(McpExplainerTools.coarseScope("1 project image")).isEqualTo("1 image");
        assertThat(McpExplainerTools.coarseScope("4 areas")).isEqualTo("1 image");   // no producer writes this
        assertThat(McpExplainerTools.coarseScope("liver_L002 (existing classifications)")).isEqualTo("1 image");
    }

    @Test
    void listResultsIsNewestFirstByTheResultsOwnTimestamp() throws Exception {
        Path results = dir.resolve(QpcatPaths.CLUSTER_RESULTS);
        SavedClusteringResult old = new SavedClusteringResult();
        old.setName("older_but_touched_last");
        old.setTimestamp("2026-01-01T00:00:00");
        Files.writeString(results.resolve("older_but_touched_last.json"), new Gson().toJson(old));
        Files.setLastModifiedTime(results.resolve("older_but_touched_last.json"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        ToolResult r = tools.get("qpcat_list_results").call(new JsonObject(), CallContext.NONE);
        JsonArray rows = JsonParser.parseString(r.texts().get(0)).getAsJsonArray();
        assertThat(rows.get(rows.size() - 1).getAsJsonObject().get("name").getAsString())
                .isEqualTo("older_but_touched_last");
    }

    @Test
    void anUnresponsiveFxThreadIsItsOwnErrorNotNoProject() throws Exception {
        McpExplainerTools blocked = new McpExplainerTools(() -> {
            throw new McpExplainerTools.ProjectUnavailableException(15);
        });
        for (Tool t : blocked.tools()) {
            ToolResult r = t.call(argsFor(t), CallContext.NONE);
            assertThat(r.isError()).isTrue();
            assertThat(r.texts().get(0))
                    .startsWith("QuPath's interface thread did not respond within 15 s; it is busy or blocked")
                    .doesNotContain("No project");
        }
    }

    @Test
    void replyClusterIdsAreReadFromTheArrayAndDroppedOnesNamed() {
        String reply = "```json\n[{\"cluster_id\":0,\"phenotype\":\"T\"},{\"cluster_id\":\"7\"},{\"cluster_id\":100},"
                + "{\"cluster_id\":100},{\"no_id\":1}]\n```";
        assertThat(McpExplainerTools.replyClusterIds(reply)).containsExactly(0, 7, 100, 100);
        ClusterExplanation kept = new ClusterExplanation();
        kept.setClusterId(0);
        assertThat(McpExplainerTools.droppedClusterIds(reply, List.of(kept))).containsExactly(7, 100);
        assertThat(McpExplainerTools.replyClusterIds("Sorry, I cannot help with that.")).isEmpty();
        assertThat(McpExplainerTools.replyClusterIds("Here you go: [{\"cluster_id\": 3}] hope it helps"))
                .containsExactly(3);
    }
}
