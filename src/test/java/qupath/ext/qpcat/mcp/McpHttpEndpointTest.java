package qupath.ext.qpcat.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.mcp.McpJsonRpc.Tool;
import qupath.ext.qpcat.mcp.McpJsonRpc.ToolResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link McpHttpEndpoint} with {@link HttpClient} and raw sockets:
 * every HTTP-level rejection, a full initialize -> tools/list -> tools/call
 * round trip against stub tools, and a half-open connection that must time
 * out without wedging the handler pool.
 */
class McpHttpEndpointTest {

    private static final String TOKEN = "test-token-0123456789abcdef";

    private McpHttpEndpoint endpoint;
    private McpJsonRpc rpc;
    private final AtomicInteger connections = new AtomicInteger();
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();

    private static final class EchoTool implements Tool {
        @Override public String name() { return "echo"; }
        @Override public String title() { return "Echo"; }
        @Override public String description() { return "Returns its text argument."; }
        @Override public JsonObject inputSchema() {
            JsonObject o = new JsonObject();
            o.addProperty("type", "object");
            return o;
        }
        @Override public ToolResult call(JsonObject arguments) {
            return ToolResult.ok("echo: " + arguments.get("text").getAsString());
        }
    }

    @BeforeEach
    void start() throws Exception {
        rpc = new McpJsonRpc("test", "instructions", List.of(new EchoTool()), 30, null);
        endpoint = new McpHttpEndpoint(0, TOKEN, rpc, connections::incrementAndGet);
    }

    @AfterEach
    void stop() {
        endpoint.close();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + endpoint.getPort() + path);
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(uri("/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder authed(String body) {
        return post(body).header("Authorization", "Bearer " + TOKEN);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String initializeBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
    }

    // ------------------------------------------------------------ rejections

    @Test
    void missingTokenIs401() throws Exception {
        HttpResponse<String> r = send(post(initializeBody()).build());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("Connection")).contains("close");
        assertThat(JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error")
                .get("message").getAsString()).contains("bearer token");
    }

    @Test
    void wrongTokenIs401EvenWhenOnlyTheLastCharacterDiffers() throws Exception {
        String almost = TOKEN.substring(0, TOKEN.length() - 1) + "X";
        HttpResponse<String> r = send(post(initializeBody())
                .header("Authorization", "Bearer " + almost).build());
        assertThat(r.statusCode()).isEqualTo(401);
        HttpResponse<String> prefix = send(post(initializeBody())
                .header("Authorization", "Bearer " + TOKEN.substring(0, 5)).build());
        assertThat(prefix.statusCode()).isEqualTo(401);
    }

    @Test
    void nonLoopbackOriginIs403() throws Exception {
        HttpResponse<String> r = send(authed(initializeBody())
                .header("Origin", "https://evil.example").build());
        assertThat(r.statusCode()).isEqualTo(403);
    }

    @Test
    void loopbackOriginIsAccepted() throws Exception {
        HttpResponse<String> r = send(authed(initializeBody())
                .header("Origin", "http://localhost:3000").build());
        assertThat(r.statusCode()).isEqualTo(200);
    }

    @Test
    void nonLoopbackHostIs403() throws Exception {
        String raw = rawRequest("POST /mcp HTTP/1.1\r\nHost: qupath.example.com\r\n"
                + "Authorization: Bearer " + TOKEN + "\r\nContent-Length: 2\r\n\r\n{}");
        assertThat(raw).startsWith("HTTP/1.1 403");
    }

    @Test
    void getIs405WithAllowPost() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/mcp")).GET().build());
        assertThat(r.statusCode()).isEqualTo(405);
        assertThat(r.headers().firstValue("Allow")).contains("POST");
    }

    @Test
    void chunkedBodyIs411() throws Exception {
        InputStream stream = new ByteArrayInputStream(initializeBody().getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/mcp"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> stream)).build());
        assertThat(r.statusCode()).isEqualTo(411);
    }

    @Test
    void missingContentLengthIs411() throws Exception {
        String raw = rawRequest("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Authorization: Bearer " + TOKEN + "\r\n\r\n");
        assertThat(raw).startsWith("HTTP/1.1 411");
    }

    @Test
    void oversizeBodyIs413BeforeTheBodyIsRead() throws Exception {
        String raw = rawRequest("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Authorization: Bearer " + TOKEN + "\r\nContent-Length: "
                + (McpHttpEndpoint.MAX_BODY_BYTES + 1) + "\r\n\r\n");
        assertThat(raw).startsWith("HTTP/1.1 413");
    }

    @Test
    void expectContinueIs417() throws Exception {
        String raw = rawRequest("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Authorization: Bearer " + TOKEN + "\r\nExpect: 100-continue\r\n"
                + "Content-Length: 2\r\n\r\n");
        assertThat(raw).startsWith("HTTP/1.1 417");
    }

    @Test
    void wrongPathIs404() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/other"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        assertThat(r.statusCode()).isEqualTo(404);
    }

    @Test
    void requestWithoutSessionIs400() throws Exception {
        HttpResponse<String> r = send(authed(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").build());
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("Mcp-Session-Id");
    }

    @Test
    void checksRunBeforeTheBodyIsConsumed() throws Exception {
        // An unauthenticated request with a huge declared body still gets its 401
        // immediately: the server must not wait for bytes it will never read.
        long t0 = System.nanoTime();
        String raw = rawRequest("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Content-Length: 500000\r\n\r\n");
        assertThat(raw).startsWith("HTTP/1.1 401");
        assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(5_000);
    }

    // ------------------------------------------------------------ round trip

    @Test
    void initializeListCallRoundTrip() throws Exception {
        HttpResponse<String> init = send(authed(initializeBody()).build());
        assertThat(init.statusCode()).isEqualTo(200);
        assertThat(init.headers().firstValue("Content-Type")).contains("application/json");
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        JsonObject initResult = JsonParser.parseString(init.body()).getAsJsonObject()
                .getAsJsonObject("result");
        assertThat(initResult.get("protocolVersion").getAsString()).isEqualTo("2025-06-18");
        assertThat(initResult.getAsJsonObject("serverInfo").get("name").getAsString()).isEqualTo("qpcat");
        assertThat(initResult.getAsJsonObject("capabilities").has("tools")).isTrue();

        HttpResponse<String> notified = send(authed(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .header("Mcp-Session-Id", session).build());
        assertThat(notified.statusCode()).isEqualTo(202);
        assertThat(notified.body()).isEmpty();

        HttpResponse<String> list = send(authed(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .header("Mcp-Session-Id", session)
                .header("MCP-Protocol-Version", "2025-06-18").build());
        assertThat(list.statusCode()).isEqualTo(200);
        JsonObject tools = JsonParser.parseString(list.body()).getAsJsonObject().getAsJsonObject("result");
        assertThat(tools.getAsJsonArray("tools")).hasSize(1);
        assertThat(tools.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString())
                .isEqualTo("echo");

        HttpResponse<String> call = send(authed(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}")
                .header("Mcp-Session-Id", session).build());
        assertThat(call.statusCode()).isEqualTo(200);
        JsonObject result = JsonParser.parseString(call.body()).getAsJsonObject().getAsJsonObject("result");
        assertThat(result.get("isError").getAsBoolean()).isFalse();
        assertThat(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                .isEqualTo("echo: hi");

        HttpResponse<String> ping = send(authed(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}")
                .header("Mcp-Session-Id", session).build());
        assertThat(ping.statusCode()).isEqualTo(200);
        assertThat(connections.get()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void sessionsAreForgottenOnTerminate() throws Exception {
        HttpResponse<String> init = send(authed(initializeBody()).build());
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        rpc.terminateSessions();
        HttpResponse<String> ping = send(authed(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}")
                .header("Mcp-Session-Id", session).build());
        assertThat(ping.statusCode()).isEqualTo(400);
    }

    // ---------------------------------------------------------- half-open

    @Test
    void truncatedRequestTimesOutWithoutWedgingThePool() throws Exception {
        // Hold one connection half-open past the pool size, then prove a real
        // request is still served and the stuck ones are closed by the timeout.
        Socket[] stuck = new Socket[McpHttpEndpoint.POOL_SIZE - 1];
        for (int i = 0; i < stuck.length; i++) {
            stuck[i] = new Socket(InetAddress.getLoopbackAddress(), endpoint.getPort());
            OutputStream out = stuck[i].getOutputStream();
            out.write("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthor".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
        try {
            long t0 = System.nanoTime();
            HttpResponse<String> live = send(authed(initializeBody()).build());
            assertThat(live.statusCode()).isEqualTo(200);
            assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(5_000);

            // The half-open sockets get a 408 after SOCKET_TIMEOUT_MS and are closed.
            stuck[0].setSoTimeout(McpHttpEndpoint.SOCKET_TIMEOUT_MS + 5_000);
            // readAllBytes returns only at EOF, so this also proves the server closed it.
            String reply = new String(stuck[0].getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(reply).startsWith("HTTP/1.1 408").contains("Connection: close");
        } finally {
            for (Socket s : stuck) s.close();
        }
    }

    // ------------------------------------------------------------- helpers

    private String rawRequest(String request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), endpoint.getPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
