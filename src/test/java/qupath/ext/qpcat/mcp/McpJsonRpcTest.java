package qupath.ext.qpcat.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.mcp.McpHttpEndpoint.HttpResponse;
import qupath.ext.qpcat.mcp.McpJsonRpc.CallContext;
import qupath.ext.qpcat.mcp.McpJsonRpc.Tool;
import qupath.ext.qpcat.mcp.McpJsonRpc.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Protocol behaviour of {@link McpJsonRpc} with the transport stubbed out. */
class McpJsonRpcTest {

    private static class Stub implements Tool {
        final String name;
        final java.util.function.Function<JsonObject, ToolResult> body;
        Stub(String name, java.util.function.Function<JsonObject, ToolResult> body) {
            this.name = name;
            this.body = body;
        }
        @Override public String name() { return name; }
        @Override public String title() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonObject inputSchema() { return new JsonObject(); }
        @Override public ToolResult call(JsonObject arguments, CallContext context) { return body.apply(arguments); }
    }

    private static Map<String, String> headers(String session) {
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.put("host", "127.0.0.1");
        if (session != null) h.put("mcp-session-id", session);
        return h;
    }

    private static JsonObject json(HttpResponse r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String init(McpJsonRpc rpc) {
        HttpResponse r = rpc.handle(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(r.status()).isEqualTo(200);
        return r.extraHeaders().get(McpJsonRpc.SESSION_HEADER);
    }

    private static String call(String tool, String args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + args + "}}";
    }

    @Test
    void parseErrorIsMinus32700() {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        HttpResponse r = rpc.handle(headers(null), "{not json");
        assertThat(json(r).getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32700);
    }

    @Test
    void batchesAreRejectedAsInvalidRequest() {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        HttpResponse r = rpc.handle(headers(null), "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        assertThat(json(r).getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32600);
    }

    @Test
    void unsupportedProtocolVersionHeaderIs400() {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        Map<String, String> h = headers(null);
        h.put("mcp-protocol-version", "1999-01-01");
        HttpResponse r = rpc.handle(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void missingSessionIs400AndUnknownSessionIs400() {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        assertThat(rpc.handle(headers(null), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").status())
                .isEqualTo(400);
        assertThat(rpc.handle(headers("nope"), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").status())
                .isEqualTo(400);
    }

    @Test
    void unknownMethodIsMinus32601() {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        String s = init(rpc);
        HttpResponse r = rpc.handle(headers(s), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}");
        assertThat(json(r).getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32601);
    }

    @Test
    void unknownToolIsMinus32602() {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        String s = init(rpc);
        HttpResponse r = rpc.handle(headers(s), call("nope", "{}"));
        assertThat(json(r).getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32602);
        assertThat(json(r).getAsJsonObject("error").get("message").getAsString()).contains("Unknown tool");
    }

    @Test
    void notificationsAre202WithNoBody() {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        String s = init(rpc);
        HttpResponse r = rpc.handle(headers(s), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).isEmpty();
    }

    @Test
    void toolExceptionBecomesIsErrorNotAProtocolError() {
        Tool boom = new Stub("boom", a -> { throw new IllegalStateException("kaput"); });
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(boom), 30, null);
        String s = init(rpc);
        JsonObject r = json(rpc.handle(headers(s), call("boom", "{}")));
        assertThat(r.has("error")).isFalse();
        JsonObject result = r.getAsJsonObject("result");
        assertThat(result.get("isError").getAsBoolean()).isTrue();
        // The agent learns the class, never the message (which may hold a path).
        assertThat(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                .contains("IllegalStateException").doesNotContain("kaput").contains("Nothing has changed");
    }

    @Test
    void rateLimitRejectsTheThirtyFirstCallWithIsError() {
        List<String> outcomes = new ArrayList<>();
        Tool ok = new Stub("ok", a -> ToolResult.ok("fine"));
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(ok), 30, (n, o) -> outcomes.add(o));
        String s = init(rpc);
        for (int i = 0; i < 30; i++) {
            JsonObject r = json(rpc.handle(headers(s), call("ok", "{}")));
            assertThat(r.getAsJsonObject("result").get("isError").getAsBoolean()).isFalse();
        }
        JsonObject limited = json(rpc.handle(headers(s), call("ok", "{}"))).getAsJsonObject("result");
        assertThat(limited.get("isError").getAsBoolean()).isTrue();
        assertThat(limited.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                .matches("Too many tool calls in the last minute \\(limit 30 per minute\\)\\. Nothing has changed\\. "
                        + "Retry after [1-9][0-9]? s\\.");
        // the refusal itself is not a counted call
        assertThat(outcomes).hasSize(30);
    }

    @Test
    void anExemptToolIsNeverRateLimitedAndValidationRunsBeforeTheLimiter() {
        Tool free = new Stub("free", a -> ToolResult.ok("fine")) {
            @Override public boolean rateLimited() { return false; }
        };
        Tool picky = new Stub("picky", a -> ToolResult.ok("ran")) {
            @Override public ToolResult validate(JsonObject a) {
                return a.has("x") ? null : ToolResult.error("x is required");
            }
        };
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(free, picky), 2, null);
        String s = init(rpc);
        for (int i = 0; i < 2; i++) {
            assertThat(json(rpc.handle(headers(s), call("picky", "{\"x\":1}"))).getAsJsonObject("result")
                    .get("isError").getAsBoolean()).isFalse();
        }
        // window exhausted: a mistaken call still gets its own refusal, not the limiter's
        JsonObject mistaken = json(rpc.handle(headers(s), call("picky", "{}"))).getAsJsonObject("result");
        assertThat(mistaken.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                .isEqualTo("x is required");
        JsonObject limited = json(rpc.handle(headers(s), call("picky", "{\"x\":1}"))).getAsJsonObject("result");
        assertThat(limited.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                .startsWith("Too many tool calls");
        for (int i = 0; i < 10; i++) {
            assertThat(json(rpc.handle(headers(s), call("free", "{}"))).getAsJsonObject("result")
                    .get("isError").getAsBoolean()).isFalse();
        }
    }

    @Test
    void initializeEchoesASupportedRequestedVersionAndOtherwiseAnswersOurs() {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        JsonObject older = json(rpc.handle(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}"));
        assertThat(older.getAsJsonObject("result").get("protocolVersion").getAsString()).isEqualTo("2025-03-26");
        JsonObject unknown = json(rpc.handle(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2030-01-01\"}}"));
        assertThat(unknown.getAsJsonObject("result").get("protocolVersion").getAsString()).isEqualTo("2025-06-18");
        JsonObject legacy = json(rpc.handle(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}"));
        assertThat(legacy.getAsJsonObject("result").get("protocolVersion").getAsString()).isEqualTo("2025-06-18");
    }

    @Test
    void sessionEvictionIsLeastRecentlyUsedNotOldestCreated() throws Exception {
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(), 30, null);
        String first = init(rpc);
        for (int i = 1; i < McpJsonRpc.MAX_SESSIONS; i++) {
            init(rpc);
            Thread.sleep(1);
        }
        // the oldest session is used again, so it must survive the next eviction
        assertThat(rpc.handle(headers(first), "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}").status()).isEqualTo(200);
        Thread.sleep(1);
        init(rpc);
        assertThat(rpc.handle(headers(first), "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\"}").status()).isEqualTo(200);
    }

    @Test
    void onlyOneToolCallRunsAtATime() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tool slow = new Stub("slow", a -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
            return ToolResult.ok("done");
        });
        McpJsonRpc rpc = new McpJsonRpc("v", "i", List.of(slow), 30, null);
        String s = init(rpc);
        AtomicReference<HttpResponse> first = new AtomicReference<>();
        Thread t = new Thread(() -> first.set(rpc.handle(headers(s), call("slow", "{}"))));
        t.start();
        entered.await();
        JsonObject second = json(rpc.handle(headers(s), call("slow", "{}"))).getAsJsonObject("result");
        assertThat(second.get("isError").getAsBoolean()).isTrue();
        assertThat(second.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                .contains("one at a time");
        release.countDown();
        t.join(5_000);
        assertThat(json(first.get()).getAsJsonObject("result").get("isError").getAsBoolean()).isFalse();
    }

    @Test
    void initializeReportsServerInfoAndInstructions() {
        McpJsonRpc rpc = new McpJsonRpc("1.2.3", "do this", List.of(), 30, null);
        HttpResponse r = rpc.handle(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        JsonObject result = json(r).getAsJsonObject("result");
        assertThat(result.getAsJsonObject("serverInfo").get("version").getAsString()).isEqualTo("1.2.3");
        assertThat(result.get("instructions").getAsString()).isEqualTo("do this");
        assertThat(result.getAsJsonObject("capabilities").getAsJsonObject("tools")
                .get("listChanged").getAsBoolean()).isFalse();
        assertThat(r.extraHeaders().get("Mcp-Session-Id")).matches("[A-Za-z0-9_-]{22}");
    }
}
