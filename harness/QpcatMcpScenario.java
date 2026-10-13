import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import qupath.ext.qpcat.mcp.McpServerController;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the QP-CAT MCP server end to end from a hidden QuPathGUI, through the
 * stand-alone Python client, against a real project with saved results.
 *
 * <p>list -> explain_prompt -> set_explanations (hand-written reply, with the
 * prompt_hash passed back) -> the saved JSON on disk carries the bundle with
 * provider MCP and the explain-time hash -> refusals (string overwrite, unknown
 * argument, missing overwrite flag, wrong prompt_hash) are answered and audited
 * -> overwrite replaces and is audited as such -> the rate limit answers at 120
 * with a retry-after while list_results stays exempt -> changing the project
 * stops the server.
 *
 * <p>args: projectDir mcpcPy [python] [--policy-disabled]
 * <br>projectDir is MODIFIED (explanations are written into a saved result); run on a copy.
 * mcpcPy is a copy of /mnt/f/claude/qupath-mcp/mcpc.py that honours MCP_PORT and MCP_TOKEN.
 * <br>With --policy-disabled the scenario sets -Dqpcat.mcp.disabled=true BEFORE the
 * controller class loads and asserts that start() refuses; nothing else runs.
 */
public final class QpcatMcpScenario {

    private static int failures = 0;

    private static void ok(String s) { System.out.println("[PASS] " + s); }
    private static void bad(String s) { System.out.println("[FAIL] " + s); failures++; }
    private static void note(String s) { System.out.println("[....] " + s); }
    private static void check(boolean cond, String pass, String fail) { if (cond) ok(pass); else bad(fail); }

    public static void main(String[] args) throws Exception {
        List<String> argv = new ArrayList<>(List.of(args));
        boolean policyDisabled = argv.remove("--policy-disabled");
        Path projectDir = Path.of(argv.get(0));
        Path mcpc = Path.of(argv.get(1));
        String python = argv.size() > 2 ? argv.get(2) : "python3";

        if (policyDisabled) {
            // Must precede the first touch of McpServerController: the flag is read at class load.
            System.setProperty("qpcat.mcp.disabled", "true");
        }

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(
                projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> { qupath.setProject(project); return null; });
        OperationLogger.getInstance().setProject(project);   // SetupQPCAT does this at install
        note("project " + projectDir);

        if (policyDisabled) {
            policyDisabledRun(qupath);
            finish();
        }

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
        check(!McpServerController.isDisabledByPolicy(), "policy switch is off in this JVM",
                "policy switch unexpectedly on");
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

        // --- qpcat_list_results: newest first by timestamp, coarse scope, no image names ---
        Run listed = run(python, mcpc, env, "qpcat_list_results", "{}");
        String resultName = null;
        if (listed.exit == 0) {
            JsonArray rows = JsonParser.parseString(listed.out.trim()).getAsJsonArray();
            ok("qpcat_list_results returned " + rows.size() + " saved result(s)");
            boolean descending = true;
            String prev = null;
            Pattern coarse = Pattern.compile("^(\\d+ (image|images|area|areas)|unknown scope)$");
            boolean scopesCoarse = true;
            for (JsonElement r : rows) {
                JsonObject o = r.getAsJsonObject();
                String ts = o.get("timestamp").getAsString();
                if (prev != null && ts.compareTo(prev) > 0) descending = false;
                prev = ts;
                if (!coarse.matcher(o.get("scope").getAsString()).matches()) scopesCoarse = false;
                if (resultName == null && !o.get("hasExplanations").getAsBoolean()) {
                    resultName = o.get("name").getAsString();
                }
            }
            check(descending, "listing is newest first by the results' own timestamps",
                    "listing is not in descending timestamp order: " + abbreviate(listed.out));
            check(scopesCoarse, "every scope is a count only (\"N images\" / \"N areas\")",
                    "a scope is not coarse: " + abbreviate(listed.out));
            List<String> imageNames = new ArrayList<>();
            for (ProjectImageEntry<BufferedImage> e : project.getImageList()) imageNames.add(e.getImageName());
            boolean leaked = false;
            for (String n : imageNames) if (!n.isBlank() && listed.out.contains(n)) leaked = true;
            check(!leaked && !listed.out.contains(projectDir.toString()),
                    "no image name or project path in the listing (" + imageNames.size() + " image names checked)",
                    "an image name or the project path appears in the listing");
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

        // --- argument validation (item 3) ---
        Run camel = run(python, mcpc, env, "qpcat_explain_prompt", "{\"result\":\"" + resultName + "\",\"topN\":3}");
        check(camel.exit != 0 && camel.out.contains("does not accept the argument(s) [topN]"),
                "unknown argument name refused, accepted names listed",
                "unknown argument: exit=" + camel.exit + " out=" + abbreviate(camel.out));
        Run strTrue = run(python, mcpc, env, "qpcat_set_explanations", "{\"result\":\"" + resultName
                + "\",\"response\":\"[]\",\"agent\":\"x\",\"overwrite\":\"true\"}");
        check(strTrue.exit != 0 && strTrue.out.contains("overwrite must be the JSON boolean"),
                "overwrite given as the string \"true\" is refused with a type-specific text",
                "string overwrite: exit=" + strTrue.exit + " out=" + abbreviate(strTrue.out));

        // --- qpcat_explain_prompt with a non-default top_n ---
        Run prompt = run(python, mcpc, env, "qpcat_explain_prompt",
                "{\"result\":\"" + resultName + "\",\"top_n\":5}");
        if (prompt.exit == 0 && prompt.out.startsWith("You are a computational pathology assistant")) {
            ok("qpcat_explain_prompt returned the prompt (" + prompt.out.length() + " chars)");
        } else {
            bad("qpcat_explain_prompt: exit=" + prompt.exit + " out=" + abbreviate(prompt.out));
        }
        String explainHash = null;
        Matcher hm = Pattern.compile("prompt_hash: ([0-9a-f]{64})").matcher(prompt.out);
        if (hm.find()) { explainHash = hm.group(1); ok("prompt hash reported"); } else bad("no prompt hash");
        check(prompt.out.contains("top_n: 5") && prompt.out.contains("prompt_hash (the value above)"),
                "metadata block says to pass prompt_hash and top_n back",
                "metadata block lacks the pass-back instruction: " + abbreviate(prompt.out.substring(
                        Math.max(0, prompt.out.length() - 900))));

        // --- hand-written reply: one row per cluster, plus one for a cluster that does not exist ---
        List<Integer> clusterIds = clusterIdsOf(projectDir, resultName);
        JsonArray reply = new JsonArray();
        for (int id : clusterIds) reply.add(row(id, "Harness phenotype " + id));
        reply.add(row(100, "Phantom cluster"));
        JsonObject setArgs = new JsonObject();
        setArgs.addProperty("result", resultName);
        setArgs.addProperty("response", reply.toString());
        setArgs.addProperty("agent", "QpcatMcpScenario (harness)");
        setArgs.addProperty("prompt_hash", explainHash != null ? explainHash : "0".repeat(64));
        setArgs.addProperty("top_n", 5);
        Run set = run(python, mcpc, env, "qpcat_set_explanations", setArgs.toString());
        int n = clusterIds.size();
        check(set.exit == 0 && set.out.startsWith("Saved " + n + " suggestions (" + n
                        + " with a phenotype, 0 the model declined or did not answer)"),
                "qpcat_set_explanations saved " + n + " suggestions and the count names the phenotypes",
                "qpcat_set_explanations: exit=" + set.exit + " out=" + abbreviate(set.out));
        check(set.out.contains("Rows for cluster ids [100] matched no cluster in this result and were dropped"),
                "the row for the non-existent cluster 100 is reported as dropped, by id",
                "dropped row not reported: " + abbreviate(set.out));
        check(set.out.contains("View Past Results"),
                "tool result tells the agent how the user reopens the result",
                "tool result lacks the reopen instructions: " + abbreviate(set.out));

        // --- the saved JSON on disk ---
        Path file = projectDir.resolve("qpcat").resolve("cluster_results").resolve(resultName + ".json");
        JsonObject saved = readJson(file);
        if (saved.has("llmExplanations")) {
            JsonObject bundle = saved.getAsJsonObject("llmExplanations");
            String provider = bundle.get("provider").getAsString();
            int stored = bundle.getAsJsonArray("explanations").size();
            check("MCP".equals(provider), "saved result has llmExplanations.provider = MCP", "provider on disk is " + provider);
            check(stored == n, "saved result has " + stored + " explanations", "saved result has " + stored + ", expected " + n);
            check("QpcatMcpScenario (harness)".equals(bundle.get("model").getAsString()),
                    "agent label stored as model", "agent label on disk: " + bundle.get("model"));
            check(explainHash != null && explainHash.equals(bundle.get("promptHash").getAsString()),
                    "stored promptHash equals the hash qpcat_explain_prompt reported (top_n 5)",
                    "stored promptHash " + bundle.get("promptHash") + " != explain hash " + explainHash);
            check(!Files.exists(file.resolveSibling(file.getFileName() + ".tmp")), "no temp file left behind",
                    "temp file left behind");
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
        check(flagged, "qpcat_list_results now reports hasExplanations = true", "hasExplanations not updated");

        // --- second write without overwrite is refused ---
        Run again = run(python, mcpc, env, "qpcat_set_explanations", setArgs.toString());
        check(again.exit != 0 && again.out.contains("already has " + n + " suggestions")
                        && again.out.contains("overwrite: true"),
                "second write without overwrite refused, naming the existing source",
                "second write: exit=" + again.exit + " out=" + abbreviate(again.out));

        // --- a wrong prompt_hash is refused even with overwrite ---
        JsonObject wrongHash = setArgs.deepCopy();
        wrongHash.addProperty("overwrite", true);
        wrongHash.addProperty("prompt_hash", "a".repeat(64));
        Run mismatch = run(python, mcpc, env, "qpcat_set_explanations", wrongHash.toString());
        check(mismatch.exit != 0 && mismatch.out.contains("does not match") && mismatch.out.contains("top_n=5"),
                "wrong prompt_hash refused, naming the mismatch and the rebuild parameters",
                "hash mismatch: exit=" + mismatch.exit + " out=" + abbreviate(mismatch.out));
        check(explainHash != null && explainHash.equals(
                        readJson(file).getAsJsonObject("llmExplanations").get("promptHash").getAsString()),
                "the refused write changed nothing on disk", "disk changed after a refused write");

        // --- overwrite: true replaces; this reply leaves one cluster unanswered ---
        JsonArray partial = new JsonArray();
        for (int i = 0; i < clusterIds.size() - 1; i++) partial.add(row(clusterIds.get(i), "Revised " + clusterIds.get(i)));
        JsonObject force = setArgs.deepCopy();
        force.addProperty("response", partial.toString());
        force.addProperty("overwrite", true);
        Run forced = run(python, mcpc, env, "qpcat_set_explanations", force.toString());
        int last = clusterIds.get(clusterIds.size() - 1);
        check(forced.exit == 0 && forced.out.contains("REPLACING"), "overwrite: true replaces and says so",
                "overwrite: exit=" + forced.exit + " out=" + abbreviate(forced.out));
        check(forced.out.startsWith("Saved " + n + " suggestions (" + (n - 1)
                        + " with a phenotype, 1 the model declined or did not answer)")
                        && forced.out.contains("Clusters left without a phenotype: [" + last + "]"),
                "the count separates answered from unanswered clusters and names the empty one",
                "count text: " + abbreviate(forced.out));

        // --- the project audit log (item 5) ---
        Path audit = projectDir.resolve("qpcat").resolve("logs")
                .resolve("qpcat_" + LocalDate.now() + ".log");
        String log = Files.exists(audit) ? Files.readString(audit, StandardCharsets.UTF_8) : "";
        check(log.contains("=== LLM EXPLAIN [REFUSED] ===") && log.contains("overwrite was not true"),
                "audit log holds a REFUSED block for the write without overwrite",
                "no REFUSED block for the missing overwrite flag in " + audit);
        check(log.contains("prompt_hash mismatch"), "audit log holds a REFUSED block for the hash mismatch",
                "no REFUSED block for the hash mismatch");
        check(log.contains("Overwrite: replaced MCP/QpcatMcpScenario (harness) written "),
                "audit log marks the overwrite with the replaced provider/label/timestamp",
                "no Overwrite row in the audit log");
        check(log.contains("Top markers: 5") && !log.contains("Top markers: 10\n  Input tokens"),
                "audit rows record the explain-time top_n", "audit rows do not carry top_n 5");
        check(log.contains("Dropped rows: cluster ids [100]"), "audit row names the dropped cluster ids",
                "no Dropped rows row");
        check(log.contains("Client: ") && !log.contains("Client: (unknown)"),
                "audit rows carry the client's User-Agent", "Client row missing or unknown");
        check(log.contains("Image: 8 images") || Pattern.compile("Image: \\d+ (images?|areas?)").matcher(log).find(),
                "audit Image row is the coarse scope, not (unknown)", "Image row not coarse: "
                        + (log.contains("Image: (unknown)") ? "(unknown) present" : "no Image row"));
        check(log.contains("--- MCP SERVER ---") && log.contains("started on 127.0.0.1 port "),
                "audit log records the server start", "no MCP SERVER start line");
        check(!log.contains(controller.getToken()), "the token is not in the audit log", "TOKEN IN AUDIT LOG");

        // --- rate limit: 120 accepted calls per minute, list_results exempt (item 3) ---
        int accepted = 0;
        String limitText = null;
        for (int i = 0; i < 130 && limitText == null; i++) {
            Run r = run(python, mcpc, env, "qpcat_explain_prompt", "{\"result\":\"" + resultName + "\",\"top_n\":1}");
            if (r.exit == 0) accepted++;
            else if (r.out.startsWith("Too many tool calls")) limitText = r.out;
            else { bad("unexpected refusal while probing the limit: " + abbreviate(r.out)); break; }
        }
        check(limitText != null && limitText.contains("limit 120 per minute")
                        && Pattern.compile("Retry after [1-9][0-9]? s\\.").matcher(limitText).find(),
                "rate limit answered with the limit (120/min) and a retry-after: " + limitText,
                "rate limit text: " + limitText + " after " + accepted + " accepted calls");
        note(accepted + " explain calls accepted in this burst before the limit (earlier calls in the window count too)");
        Run exempt = run(python, mcpc, env, "qpcat_list_results", "{}");
        check(exempt.exit == 0, "qpcat_list_results still answers while the limit is in force",
                "list_results limited: " + abbreviate(exempt.out));
        Run mistakeWhileLimited = run(python, mcpc, env, "qpcat_explain_prompt", "{\"result\":\"nope\"}");
        check(mistakeWhileLimited.out.contains("No saved clustering result named"),
                "a mistaken call gets its own refusal, not the limiter's, while limited",
                "while limited: " + abbreviate(mistakeWhileLimited.out));

        int calls = controller.toolCallCountProperty().get();
        check(calls >= 10, "controller counted " + calls + " tool calls", "tool call count " + calls);

        // --- project change stops the server ---
        String url = controller.getUrl();
        onFx(() -> { qupath.setProject(null); return null; });
        Thread.sleep(300);
        check(!controller.isRunning() && controller.getToken() == null,
                "project change stopped the server and cleared the token", "server still running after project change");
        try {
            http.send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            bad("port still accepting connections after stop");
        } catch (ConnectException refused) {
            ok("port refuses connections after stop");
        }
        check("stopped: project changed".equals(controller.lastEventProperty().get()),
                "last event: stopped: project changed", "last event: " + controller.lastEventProperty().get());
        String log2 = Files.exists(audit) ? Files.readString(audit, StandardCharsets.UTF_8) : "";
        check(log2.contains("stopped (reason: project changed)"), "audit log records the stop with its reason",
                "no MCP SERVER stop line");

        finish();
    }

    /** With -Dqpcat.mcp.disabled=true set before the class loaded: start() must refuse. */
    private static void policyDisabledRun(QuPathGUI qupath) throws Exception {
        McpServerController controller = McpServerController.getInstance();
        check(McpServerController.isDisabledByPolicy(), "isDisabledByPolicy() reads the property",
                "isDisabledByPolicy() is false with the property set");
        onFx(() -> { controller.bindToProject(qupath); return null; });
        try {
            controller.start();
            bad("start() did not throw with qpcat.mcp.disabled=true");
        } catch (IOException e) {
            check(e.getMessage().equals(
                            "The MCP server is disabled on this installation (qpcat.mcp.disabled=true)."),
                    "start() refused: " + e.getMessage(), "start() threw the wrong text: " + e.getMessage());
        }
        check(!controller.isRunning() && controller.getToken() == null && controller.getPort() == 0,
                "nothing is running, no token, no port", "controller shows state after a refused start");
    }

    private static JsonObject row(int id, String phenotype) {
        JsonObject row = new JsonObject();
        row.addProperty("cluster_id", id);
        row.addProperty("phenotype", phenotype);
        row.addProperty("confidence", "low");
        row.addProperty("rationale", "Written by QpcatMcpScenario, not a model.");
        JsonArray markers = new JsonArray();
        markers.add("CD3");
        row.add("supporting_markers", markers);
        return row;
    }

    private static JsonObject readJson(Path file) throws IOException {
        return JsonParser.parseReader(Files.newBufferedReader(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static List<Integer> clusterIdsOf(Path projectDir, String resultName) throws IOException {
        Path file = projectDir.resolve("qpcat").resolve("cluster_results").resolve(resultName + ".json");
        JsonObject saved = readJson(file);
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
