package qupath.ext.qpcat.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback-only HTTP/1.1 listener for the MCP endpoint, built on
 * {@link ServerSocket} so it runs on QuPath's jlinked runtime.
 * <p>
 * Accepts {@code POST /mcp} with a {@code Content-Length} body of at most
 * {@link #MAX_BODY_BYTES}; validates {@code Host}, {@code Origin} and the
 * bearer token before the body is read; every response carries
 * {@code Connection: close}.
 */
public final class McpHttpEndpoint implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(McpHttpEndpoint.class);

    public static final String PATH = "/mcp";
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    static final int MAX_HEADER_BYTES = 16 * 1024;
    static final int SOCKET_TIMEOUT_MS = 10_000;
    static final int POOL_SIZE = 4;

    /** Handles an authenticated POST to the endpoint. */
    @FunctionalInterface
    public interface Handler {
        HttpResponse handle(Map<String, String> headers, String body);
    }

    /** One HTTP response; {@code extraHeaders} may be null. */
    public record HttpResponse(int status, String body, Map<String, String> extraHeaders) {
        public static HttpResponse json(int status, String body) {
            return new HttpResponse(status, body, null);
        }
        public static HttpResponse empty(int status) {
            return new HttpResponse(status, "", null);
        }
    }

    private final byte[] tokenBytes;
    private final Handler handler;
    private final Runnable activityListener;
    private final ServerSocket serverSocket;
    private final ThreadPoolExecutor pool;
    private final Thread acceptor;
    private volatile boolean closed;

    /**
     * Binds the endpoint to {@code port} on the loopback address.
     *
     * @param port             TCP port to bind
     * @param token            bearer token clients must present
     * @param handler          receives every authenticated POST
     * @param activityListener invoked on every accepted connection; may be null
     * @throws IOException if the port cannot be bound
     */
    public McpHttpEndpoint(int port, String token, Handler handler, Runnable activityListener)
            throws IOException {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("token must not be empty");
        }
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        this.handler = handler;
        this.activityListener = activityListener != null ? activityListener : () -> { };
        // Explicit no-arg constructor + bind: the (int) and (int,int) forms bind
        // every interface.
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        } catch (IOException e) {
            try { ss.close(); } catch (IOException ignored) { }
            throw e;
        }
        this.serverSocket = ss;
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), r -> {
                    Thread t = new Thread(r, "QPCAT-MCP-handler-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.acceptor = new Thread(this::acceptLoop, "QPCAT-MCP-acceptor");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /** @return the bound TCP port */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Stops accepting, closes the listening socket and the handler pool. Idempotent. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { serverSocket.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!closed) logger.debug("MCP accept failed: {}", e.getMessage());
                continue;
            }
            activityListener.run();
            try {
                pool.execute(() -> serve(socket));
            } catch (RejectedExecutionException busy) {
                try {
                    socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                    writeResponse(socket.getOutputStream(), HttpResponse.json(503,
                            McpJsonRpc.httpErrorBody("Server busy; retry shortly.")), null);
                } catch (IOException ignored) {
                } finally {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            HttpResponse response;
            String allow = null;
            try {
                response = dispatch(in);
                if (response.status() == 405) allow = "POST";
            } catch (SocketTimeoutException e) {
                response = HttpResponse.json(408, McpJsonRpc.httpErrorBody("Request timed out."));
            } catch (BadRequest e) {
                response = HttpResponse.json(e.status, McpJsonRpc.httpErrorBody(e.getMessage()));
                if (e.status == 405) allow = "POST";
            }
            writeResponse(out, response, allow);
        } catch (SocketException e) {
            logger.debug("MCP connection dropped: {}", e.getMessage());
        } catch (IOException e) {
            logger.debug("MCP connection failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("MCP handler failed: {}", e.toString());
        }
    }

    /** HTTP-level rejection carrying its status. */
    private static final class BadRequest extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private HttpResponse dispatch(InputStream in) throws IOException {
        byte[] head = readHead(in);
        String headText = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = headText.split("\r?\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new BadRequest(400, "Malformed request line.");
        }
        String[] requestLine = lines[0].trim().split(" ");
        if (requestLine.length != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            throw new BadRequest(400, "Malformed request line.");
        }
        String method = requestLine[0];
        String target = requestLine[1];
        Map<String, String> headers = parseHeaders(lines);

        String path = target;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (!PATH.equals(path)) {
            throw new BadRequest(404, "Not found.");
        }
        if ("GET".equals(method) || "DELETE".equals(method)) {
            throw new BadRequest(405, "Method not allowed; this endpoint only accepts POST.");
        }
        if (!"POST".equals(method)) {
            throw new BadRequest(404, "Not found.");
        }

        String host = headers.get("host");
        if (host == null || !isLoopbackAuthority(host)) {
            logger.warn("MCP request rejected: Host is not loopback ({})", describe(host));
            throw new BadRequest(403, "Forbidden: Host must be loopback.");
        }
        String origin = headers.get("origin");
        if (origin != null && !isLoopbackOrigin(origin)) {
            logger.warn("MCP request rejected: Origin is not loopback ({})", describe(origin));
            throw new BadRequest(403, "Forbidden: Origin must be loopback.");
        }
        if (!tokenMatches(headers.get("authorization"))) {
            logger.warn("MCP request rejected: missing or invalid bearer token");
            throw new BadRequest(401, "Unauthorized: missing or invalid bearer token.");
        }

        String expect = headers.get("expect");
        if (expect != null && expect.toLowerCase(Locale.ROOT).contains("100-continue")) {
            throw new BadRequest(417, "Expectation failed: send the body without Expect.");
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new BadRequest(411, "Length required: chunked bodies are not accepted.");
        }
        String lengthHeader = headers.get("content-length");
        if (lengthHeader == null) {
            throw new BadRequest(411, "Length required.");
        }
        long length;
        try {
            length = Long.parseLong(lengthHeader.trim());
        } catch (NumberFormatException e) {
            throw new BadRequest(400, "Malformed Content-Length.");
        }
        if (length < 0) throw new BadRequest(400, "Malformed Content-Length.");
        if (length > MAX_BODY_BYTES) {
            throw new BadRequest(413, "Payload too large: the limit is " + MAX_BODY_BYTES + " bytes.");
        }
        byte[] body = readExactly(in, (int) length);
        return handler.handle(headers, new String(body, StandardCharsets.UTF_8));
    }

    private boolean tokenMatches(String authorization) {
        if (authorization == null) return false;
        String a = authorization.trim();
        if (a.length() < 7 || !a.substring(0, 7).equalsIgnoreCase("Bearer ")) return false;
        byte[] presented = a.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(tokenBytes, presented);
    }

    /** True for {@code 127.0.0.1}, {@code localhost} or {@code [::1]}, with an optional port. */
    static boolean isLoopbackAuthority(String authority) {
        if (authority == null) return false;
        String a = authority.trim().toLowerCase(Locale.ROOT);
        String hostPart;
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end < 0) return false;
            hostPart = a.substring(0, end + 1);
            String rest = a.substring(end + 1);
            if (!rest.isEmpty() && !isPort(rest)) return false;
        } else {
            int colon = a.indexOf(':');
            hostPart = colon >= 0 ? a.substring(0, colon) : a;
            if (colon >= 0 && !isPort(a.substring(colon))) return false;
        }
        return hostPart.equals("127.0.0.1") || hostPart.equals("localhost") || hostPart.equals("[::1]");
    }

    /** True for {@code http(s)://} followed by a loopback authority. */
    static boolean isLoopbackOrigin(String origin) {
        String o = origin.trim().toLowerCase(Locale.ROOT);
        String rest;
        if (o.startsWith("http://")) rest = o.substring(7);
        else if (o.startsWith("https://")) rest = o.substring(8);
        else return false;
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (rest.isEmpty() || rest.contains("/")) return false;
        return isLoopbackAuthority(rest);
    }

    private static boolean isPort(String colonAndDigits) {
        if (colonAndDigits.length() < 2 || colonAndDigits.charAt(0) != ':') return false;
        for (int i = 1; i < colonAndDigits.length(); i++) {
            if (!Character.isDigit(colonAndDigits.charAt(i))) return false;
        }
        return true;
    }

    /** Safe-to-log rendering of a header value: control chars stripped, truncated. */
    static String describe(String value) {
        if (value == null) return "absent";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length() && sb.length() < 80; i++) {
            char c = value.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return sb.toString();
    }

    private static Map<String, String> parseHeaders(String[] lines) throws BadRequest {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new BadRequest(400, "Obsolete header folding is not accepted.");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) throw new BadRequest(400, "Malformed header.");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (headers.containsKey(name)) {
                if (name.equals("content-length") || name.equals("host")
                        || name.equals("authorization") || name.equals("transfer-encoding")) {
                    throw new BadRequest(400, "Duplicate " + name + " header.");
                }
                value = headers.get(name) + ", " + value;
            }
            headers.put(name, value);
        }
        return headers;
    }

    /** Reads up to and including the blank line that ends the header block. */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int prev2 = -1, prev1 = -1;
        while (true) {
            int b = in.read();
            if (b < 0) throw new BadRequest(400, "Connection closed before the headers ended.");
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) {
                throw new BadRequest(431, "Request headers too large.");
            }
            // Ends at an empty line: LF LF, or CR LF CR LF (prev2 = LF, prev1 = CR).
            if (b == '\n' && (prev1 == '\n' || (prev1 == '\r' && prev2 == '\n'))) {
                return buf.toByteArray();
            }
            prev2 = prev1;
            prev1 = b;
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int r = in.read(data, off, length - off);
            if (r < 0) throw new BadRequest(400, "Connection closed before the body ended.");
            off += r;
        }
        return data;
    }

    private static void writeResponse(OutputStream out, HttpResponse response, String allow)
            throws IOException {
        byte[] body = response.body() != null
                ? response.body().getBytes(StandardCharsets.UTF_8) : new byte[0];
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(response.status()).append(' ')
                .append(reason(response.status())).append("\r\n");
        if (body.length > 0) {
            sb.append("Content-Type: application/json\r\n");
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Cache-Control: no-store\r\n");
        if (allow != null) sb.append("Allow: ").append(allow).append("\r\n");
        if (response.extraHeaders() != null) {
            for (Map.Entry<String, String> e : response.extraHeaders().entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 417: return "Expectation Failed";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }

}
