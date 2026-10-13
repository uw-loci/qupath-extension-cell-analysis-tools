import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import qupath.ext.qpcat.mcp.McpServerController;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Drives the QP-CAT MCP server end to end from a hidden QuPathGUI, through the
 * stand-alone Python client, against a real project with saved results.
 *
 * <p>list -> explain_prompt -> set_explanations (hand-written reply) -> the saved
 * JSON on disk carries the bundle with provider MCP -> a second set_explanations
 * without overwrite is refused -> changing the project stops the server.
 *
 * <p>args: projectDir mcpcPy [python]
 * <br>projectDir is MODIFIED (explanations are written into a saved result); run on a copy.
 * mcpcPy is a copy of /mnt/f/claude/qupath-mcp/mcpc.py that honours MCP_PORT and MCP_TOKEN.
 */
public final class QpcatMcpScenario {

    private static int failures = 0;

    private static void ok(String s) { System.out.println("[PASS] " + s); }
    private static void bad(String s) { System.out.println("[FAIL] " + s); failures++; }
    private static void note(String s) { System.out.println("[....] " + s); }

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        Path mcpc = Path.of(args[1]);
        String python = args.length > 2 ? args[2] : "python3";

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(
                projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> { qupath.setProject(project); return null; });
        note("project " + projectDir);

        // A hidden QuPathGUI never runs the extension's installation hook, so the
        // Appose worker is not started for us -- the tools refuse without it.
        ApposeClusteringService service = ApposeClusteringService.getInstance();
        if (!service.isAvailable()) {
            note("starting the QPCAT Python service...");
            service.initialize(QpcatMcpScenario::note);
        }
        if (!service.isAvailable()) {
            bad("QPCAT service did not start: " + service.getInitError());
            finish();
        }

        McpServerController controller = McpServerController.getInstance();
        // SetupQPCAT does this once at install; here the scenario stands in for it.
        onFx(() -> { controller.bindToProject(qupath); return null; });
        controller.start();
        if (controller.isRunning() && controller.getToken() != null) {
            ok("server started: " + controller.getUrl() + " (token held, not printed)");
        } else {
            bad("server did not start");
            finish();
        }
        Map<String, String> env = Map.of(
                "MCP_PORT", String.valueOf(controller.getPort()),
                "MCP_TOKEN", controller.getToken());

        // --- an unauthenticated probe must be refused before anything else ---
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> unauth = http.send(HttpRequest.newBuilder(URI.create(controller.getUrl()))
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (unauth.statusCode() == 401) ok("no token -> 401"); else bad("no token -> " + unauth.statusCode());

        // --- tools/list through the Python client ---
        Run list = run(python, mcpc, env, "--list");
        if (list.exit == 0 && list.out.contains("qpcat_list_results")
                && list.out.contains("qpcat_explain_prompt") && list.out.contains("qpcat_set_explanations")) {
            ok("tools/list names the three tools");
        } else {
            bad("tools/list: exit=" + list.exit + " out=" + list.out);
        }

        // --- qpcat_list_results ---
        Run listed = run(python, mcpc, env, "qpcat_list_results", "{}");
        String resultName = null;
        JsonArray rows = null;
        if (listed.exit == 0) {
            rows = JsonParser.parseString(listed.out.trim()).getAsJsonArray();
            ok("qpcat_list_results returned " + rows.size() + " saved result(s)");
            for (JsonElement r : rows) {
                JsonObject o = r.getAsJsonObject();
                if (!o.get("hasExplanations").getAsBoolean()) {
                    resultName = o.get("name").getAsString();
                    break;
                }
            }
        } else {
            bad("qpcat_list_results: " + listed.out);
        }
        if (resultName == null) {
            bad("no saved result without explanations to work on");
            finish();
        }
        note("working on result '" + resultName + "'");

        // --- an unknown name is refused without a path ---
        Run unknown = run(python, mcpc, env, "qpcat_explain_prompt", "{\"result\":\"../" + resultName + "\"}");
        if (unknown.exit != 0 && unknown.out.contains("No saved clustering result named")
                && !unknown.out.contains(projectDir.toString())) {
            ok("traversal name refused, no path echoed");
        } else {
            bad("traversal name: exit=" + unknown.exit + " out=" + unknown.out);
        }

        // --- qpcat_explain_prompt ---
        Run prompt = run(python, mcpc, env, "qpcat_explain_prompt",
                "{\"result\":\"" + resultName + "\",\"top_n\":5}");
        if (prompt.exit == 0 && prompt.out.startsWith("You are a computational pathology assistant")) {
            ok("qpcat_explain_prompt returned the prompt (" + prompt.out.length() + " chars)");
        } else {
            bad("qpcat_explain_prompt: exit=" + prompt.exit + " out=" + abbreviate(prompt.out));
        }
        if (prompt.out.contains("prompt_hash: ")) ok("prompt hash reported"); else bad("no prompt hash");

        // --- hand-written reply, one row per cluster the saved result has ---
        List<Integer> clusterIds = clusterIdsOf(projectDir, resultName);
        JsonArray reply = new JsonArray();
        for (int id : clusterIds) {
            JsonObject row = new JsonObject();
            row.addProperty("cluster_id", id);
            row.addProperty("phenotype", "Harness phenotype " + id);
            row.addProperty("confidence", "low");
            row.addProperty("rationale", "Written by QpcatMcpScenario, not a model.");
            JsonArray markers = new JsonArray();
            markers.add("CD3");
            row.add("supporting_markers", markers);
            reply.add(row);
        }
        JsonObject setArgs = new JsonObject();
        setArgs.addProperty("result", resultName);
        setArgs.addProperty("response", reply.toString());
        setArgs.addProperty("agent", "QpcatMcpScenario (harness)");
        Run set = run(python, mcpc, env, "qpcat_set_explanations", setArgs.toString());
        if (set.exit == 0 && set.out.startsWith("Saved " + clusterIds.size() + " suggestions")) {
            ok("qpcat_set_explanations saved " + clusterIds.size() + " suggestions");
        } else {
            bad("qpcat_set_explanations: exit=" + set.exit + " out=" + abbreviate(set.out));
        }
        if (set.out.contains("View Past Results")) {
            ok("tool result tells the agent how the user reopens the result");
        } else {
            bad("tool result lacks the reopen instructions: " + abbreviate(set.out));
        }

        // --- the saved JSON on disk ---
        Path file = projectDir.resolve("qpcat").resolve("cluster_results").resolve(resultName + ".json");
        JsonObject saved = JsonParser.parseReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (saved.has("llmExplanations")) {
            JsonObject bundle = saved.getAsJsonObject("llmExplanations");
            String provider = bundle.get("provider").getAsString();
            int n = bundle.getAsJsonArray("explanations").size();
            if ("MCP".equals(provider)) ok("saved result has llmExplanations.provider = MCP");
            else bad("provider on disk is " + provider);
            if (n == clusterIds.size()) ok("saved result has " + n + " explanations");
            else bad("saved result has " + n + " explanations, expected " + clusterIds.size());
            if ("QpcatMcpScenario (harness)".equals(bundle.get("model").getAsString())) {
                ok("agent label stored as model");
            } else {
                bad("agent label on disk: " + bundle.get("model"));
            }
            if (!Files.exists(file.resolveSibling(file.getFileName() + ".tmp"))) ok("no temp file left behind");
            else bad("temp file left behind");
        } else {
            bad("saved result has no llmExplanations after the write");
        }

        // --- listing now reports hasExplanations ---
        Run relisted = run(python, mcpc, env, "qpcat_list_results", "{}");
        boolean flagged = false;
        for (JsonElement r : JsonParser.parseString(relisted.out.trim()).getAsJsonArray()) {
            JsonObject o = r.getAsJsonObject();
            if (o.get("name").getAsString().equals(resultName)) flagged = o.get("hasExplanations").getAsBoolean();
        }
        if (flagged) ok("qpcat_list_results now reports hasExplanations = true"); else bad("hasExplanations not updated");

        // --- second write without overwrite is refused ---
        Run again = run(python, mcpc, env, "qpcat_set_explanations", setArgs.toString());
        if (again.exit != 0 && again.out.contains("already has " + clusterIds.size() + " suggestions")
                && again.out.contains("overwrite: true")) {
            ok("second write without overwrite refused, naming the existing source");
        } else {
            bad("second write: exit=" + again.exit + " out=" + abbreviate(again.out));
        }
        setArgs.addProperty("overwrite", true);
        Run forced = run(python, mcpc, env, "qpcat_set_explanations", setArgs.toString());
        if (forced.exit == 0 && forced.out.contains("REPLACING")) ok("overwrite: true replaces and says so");
        else bad("overwrite: exit=" + forced.exit + " out=" + abbreviate(forced.out));

        int calls = controller.toolCallCountProperty().get();
        if (calls >= 7) ok("controller counted " + calls + " tool calls"); else bad("tool call count " + calls);

        // --- project change stops the server ---
        String url = controller.getUrl();
        onFx(() -> { qupath.setProject(null); return null; });
        Thread.sleep(300);
        if (!controller.isRunning() && controller.getToken() == null) {
            ok("project change stopped the server and cleared the token");
        } else {
            bad("server still running after project change");
        }
        try {
            http.send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            bad("port still accepting connections after stop");
        } catch (ConnectException refused) {
            ok("port refuses connections after stop");
        }
        if ("stopped: project changed".equals(controller.lastEventProperty().get())) {
            ok("last event: stopped: project changed");
        } else {
            bad("last event: " + controller.lastEventProperty().get());
        }

        finish();
    }

    private static List<Integer> clusterIdsOf(Path projectDir, String resultName) throws IOException {
        Path file = projectDir.resolve("qpcat").resolve("cluster_results").resolve(resultName + ".json");
        JsonObject saved = JsonParser.parseReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<Integer> ids = new ArrayList<>();
        JsonObject markers = JsonParser.parseString(saved.get("markerRankingsJson").getAsString()).getAsJsonObject();
        for (String key : markers.keySet()) ids.add(Integer.parseInt(key));
        ids.sort(null);
        return ids;
    }

    private record Run(int exit, String out) { }

    private static Run run(String python, Path mcpc, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(python);
        cmd.add("-I");
        cmd.add(mcpc.toString());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(600, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Run(-1, "TIMEOUT " + out);
        }
        if (out.startsWith("ERROR: ")) out = out.substring(7);
        return new Run(p.exitValue(), out.stripTrailing());
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 600 ? s : s.substring(0, 600) + "... (" + s.length() + " chars)";
    }

    private static void finish() {
        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        McpServerController.getInstance().stop("scenario finished");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void startFx() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
        } catch (IllegalStateException alreadyRunning) {
            up.countDown();
        }
        if (!up.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX did not start");
        }
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(600, TimeUnit.SECONDS);
    }
}
