package qupath.ext.qpcat.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.mcp.McpHttpEndpoint.HttpResponse;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * The MCP JSON-RPC subset over Gson: {@code initialize}, {@code ping},
 * {@code tools/list}, {@code tools/call}, plus notifications (202, no body).
 * Issues an {@code Mcp-Session-Id} on initialize and requires it afterwards.
 * Tool failures are {@code result.isError = true}; protocol failures are
 * JSON-RPC errors.
 */
public final class McpJsonRpc implements McpHttpEndpoint.Handler {

    private static final Logger logger = LoggerFactory.getLogger(McpJsonRpc.class);

    public static final String PROTOCOL_VERSION = "2025-06-18";
    static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final int MAX_SESSIONS = 16;
    static final String SESSION_HEADER = "Mcp-Session-Id";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int SERVER_ERROR = -32000;

    /** One MCP tool. */
    public interface Tool {
        String name();
        String title();
        String description();
        /** JSON Schema of the {@code arguments} object. */
        JsonObject inputSchema();
        ToolResult call(JsonObject arguments) throws Exception;
    }

    /** Text content blocks plus the error flag. */
    public record ToolResult(List<String> texts, boolean isError) {
        public static ToolResult ok(String... texts) {
            return new ToolResult(List.of(texts), false);
        }
        public static ToolResult error(String text) {
            return new ToolResult(List.of(text), true);
        }
    }

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String serverVersion;
    private final String instructions;
    private final List<Tool> tools;
    private final RateLimiter rateLimiter;
    private final AtomicBoolean callInFlight = new AtomicBoolean(false);
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final BiConsumer<String, String> toolCallListener;

    /**
     * @param serverVersion    reported in {@code serverInfo.version}
     * @param instructions     server instructions text for the agent
     * @param tools            the tool set, fixed for the server's life
     * @param maxCallsPerMinute token-bucket size for {@code tools/call}
     * @param toolCallListener receives (toolName, outcome) after every accepted call; may be null
     */
    public McpJsonRpc(String serverVersion, String instructions, List<Tool> tools,
                      int maxCallsPerMinute, BiConsumer<String, String> toolCallListener) {
        this.serverVersion = serverVersion;
        this.instructions = instructions;
        this.tools = List.copyOf(tools);
        this.rateLimiter = new RateLimiter(maxCallsPerMinute, 60_000_000_000L);
        this.toolCallListener = toolCallListener != null ? toolCallListener : (a, b) -> { };
    }

    /** Forgets every session; later requests with an old id get 400. */
    public void terminateSessions() {
        sessions.clear();
    }

    @Override
    public HttpResponse handle(Map<String, String> headers, String body) {
        String declared = headers.get("mcp-protocol-version");
        if (declared != null && !SUPPORTED_PROTOCOL_VERSIONS.contains(declared.trim())) {
            return HttpResponse.json(400, errorBody(JsonNull.INSTANCE, INVALID_REQUEST,
                    "Unsupported MCP-Protocol-Version: " + McpHttpEndpoint.describe(declared)));
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return HttpResponse.json(200, errorBody(JsonNull.INSTANCE, PARSE_ERROR, "Parse error."));
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return HttpResponse.json(200, errorBody(JsonNull.INSTANCE, INVALID_REQUEST,
                    parsed != null && parsed.isJsonArray()
                            ? "Batch requests are not supported."
                            : "A JSON-RPC request must be an object."));
        }
        JsonObject msg = parsed.getAsJsonObject();
        JsonElement id = msg.has("id") ? msg.get("id") : null;
        String method = msg.has("method") && msg.get("method").isJsonPrimitive()
                ? msg.get("method").getAsString() : null;

        if (method == null) {
            // A response or malformed message from the client: acknowledge and drop.
            return msg.has("result") || msg.has("error")
                    ? HttpResponse.empty(202)
                    : HttpResponse.json(200, errorBody(id, INVALID_REQUEST, "Missing method."));
        }
        if (!"2.0".equals(stringOrNull(msg.get("jsonrpc")))) {
            return HttpResponse.json(200, errorBody(id, INVALID_REQUEST, "jsonrpc must be \"2.0\"."));
        }

        if ("initialize".equals(method)) {
            return initialize(id);
        }

        String sessionId = headers.get("mcp-session-id");
        if (sessionId == null || !sessions.containsKey(sessionId)) {
            return HttpResponse.json(400, errorBody(id, INVALID_REQUEST,
                    sessionId == null
                            ? "Missing Mcp-Session-Id header; call initialize first."
                            : "Unknown Mcp-Session-Id; call initialize again."));
        }

        if (method.startsWith("notifications/")) {
            return HttpResponse.empty(202);
        }
        if (id == null) {
            return HttpResponse.json(200, errorBody(JsonNull.INSTANCE, INVALID_REQUEST,
                    "A request needs an id; notifications use the notifications/ prefix."));
        }
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                ? msg.getAsJsonObject("params") : new JsonObject();

        switch (method) {
            case "ping":
                return HttpResponse.json(200, resultBody(id, new JsonObject()));
            case "tools/list":
                return HttpResponse.json(200, resultBody(id, toolsList()));
            case "tools/call":
                return toolsCall(id, params);
            default:
                return HttpResponse.json(200, errorBody(id, METHOD_NOT_FOUND,
                        "Method not found: " + McpHttpEndpoint.describe(method)));
        }
    }

    private HttpResponse initialize(JsonElement id) {
        if (id == null) {
            return HttpResponse.json(200, errorBody(JsonNull.INSTANCE, INVALID_REQUEST,
                    "initialize is a request and needs an id."));
        }
        String sessionId = newSessionId();
        if (sessions.size() >= MAX_SESSIONS) {
            sessions.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(oldest -> sessions.remove(oldest.getKey()));
        }
        sessions.put(sessionId, System.nanoTime());

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "qpcat");
        serverInfo.addProperty("title", "QP-CAT Cluster Explainer");
        serverInfo.addProperty("version", serverVersion);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", instructions);
        logger.info("MCP session initialized ({} active)", sessions.size());
        return new HttpResponse(200, resultBody(id, result), Map.of(SESSION_HEADER, sessionId));
    }

    private JsonObject toolsList() {
        JsonArray arr = new JsonArray();
        for (Tool t : tools) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("title", t.title());
            o.addProperty("description", t.description());
            o.add("inputSchema", t.inputSchema());
            arr.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("tools", arr);
        return result;
    }

    private HttpResponse toolsCall(JsonElement id, JsonObject params) {
        String name = stringOrNull(params.get("name"));
        if (name == null) {
            return HttpResponse.json(200, errorBody(id, INVALID_PARAMS, "Missing tool name."));
        }
        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return HttpResponse.json(200, errorBody(id, INVALID_PARAMS,
                    "Unknown tool: " + McpHttpEndpoint.describe(name)));
        }
        JsonElement argsEl = params.get("arguments");
        if (argsEl != null && !argsEl.isJsonNull() && !argsEl.isJsonObject()) {
            return HttpResponse.json(200, errorBody(id, INVALID_PARAMS, "arguments must be an object."));
        }
        JsonObject arguments = argsEl != null && argsEl.isJsonObject()
                ? argsEl.getAsJsonObject() : new JsonObject();

        if (!rateLimiter.tryAcquire()) {
            logger.info("MCP tool call rejected: rate limit (tool={})", name);
            return HttpResponse.json(200, resultBody(id, toolResultJson(ToolResult.error(
                    "Too many tool calls in the last minute (limit " + rateLimiter.capacity
                            + "). Nothing has changed. Wait a moment and try again."))));
        }
        if (!callInFlight.compareAndSet(false, true)) {
            logger.info("MCP tool call rejected: another call is in flight (tool={})", name);
            return HttpResponse.json(200, resultBody(id, toolResultJson(ToolResult.error(
                    "Another tool call is still running; this server handles one at a time. "
                            + "Nothing has changed. Wait for it to finish and try again."))));
        }
        long t0 = System.nanoTime();
        ToolResult result;
        try {
            result = tool.call(arguments);
        } catch (Exception e) {
            logger.warn("MCP tool '{}' failed: {}", name, e.toString());
            result = ToolResult.error("The tool failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "")
                    + ". Nothing has changed.");
        } finally {
            callInFlight.set(false);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        String outcome = result.isError() ? "error" : "ok";
        logger.info("MCP tool call: {} -> {} ({} ms)", name, outcome, ms);
        toolCallListener.accept(name, outcome + " (" + ms + " ms)");
        return HttpResponse.json(200, resultBody(id, toolResultJson(result)));
    }

    static JsonObject toolResultJson(ToolResult result) {
        JsonArray content = new JsonArray();
        for (String text : result.texts()) {
            JsonObject c = new JsonObject();
            c.addProperty("type", "text");
            c.addProperty("text", text != null ? text : "");
            content.add(c);
        }
        JsonObject o = new JsonObject();
        o.add("content", content);
        o.addProperty("isError", result.isError());
        return o;
    }

    static String resultBody(JsonElement id, JsonElement result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id != null ? id : JsonNull.INSTANCE);
        o.add("result", result);
        return GSON.toJson(o);
    }

    static String errorBody(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id != null ? id : JsonNull.INSTANCE);
        o.add("error", err);
        return GSON.toJson(o);
    }

    /** JSON-RPC-shaped body for an HTTP-level rejection. */
    static String httpErrorBody(String message) {
        return errorBody(JsonNull.INSTANCE, SERVER_ERROR, message);
    }

    private static String stringOrNull(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static String newSessionId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Sliding-window limiter: at most {@code capacity} acquisitions per window. */
    static final class RateLimiter {
        final int capacity;
        private final long windowNanos;
        private final Deque<Long> stamps = new ArrayDeque<>();

        RateLimiter(int capacity, long windowNanos) {
            this.capacity = Math.max(1, capacity);
            this.windowNanos = windowNanos;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            while (!stamps.isEmpty() && now - stamps.peekFirst() > windowNanos) {
                stamps.pollFirst();
            }
            if (stamps.size() >= capacity) return false;
            stamps.addLast(now);
            return true;
        }
    }
}
