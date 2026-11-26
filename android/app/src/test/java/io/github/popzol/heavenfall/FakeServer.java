package io.github.popzol.heavenfall;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small local HTTP server for the tests (plain sockets: the Android test classpath has no JDK HTTP server), with
 * the layout a test base URL has: {@code /api/...} stands for api.github.com and {@code /github/...} for github.com.
 * Every request is logged (its path and its Range header). One request per connection, "Connection: close".
 */
final class FakeServer implements AutoCloseable {
    interface Handler {
        void handle(Exchange exchange) throws IOException;
    }

    /** The request and the answer being written. */
    static final class Exchange {
        private final Socket socket;
        private final Map<String, String> requestHeaders;
        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private OutputStream body;
        private boolean closed;
        final String path;

        Exchange(Socket socket, String path, Map<String, String> requestHeaders) {
            this.socket = socket;
            this.path = path;
            this.requestHeaders = requestHeaders;
        }

        Headers getRequestHeaders() {
            return new Headers(requestHeaders);
        }

        Headers getResponseHeaders() {
            return new Headers(responseHeaders);
        }

        /** The status and the headers; a length of -1 means no body. */
        void sendResponseHeaders(int code, long length) throws IOException {
            StringBuilder sb = new StringBuilder("HTTP/1.1 " + code + " Status\r\n");
            for (Map.Entry<String, String> e : responseHeaders.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("Content-Length: ").append(Math.max(0, length)).append("\r\nConnection: close\r\n\r\n");
            body = socket.getOutputStream();
            body.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            body.flush();
        }

        OutputStream getResponseBody() {
            return body;
        }

        /** Closes the connection (also cuts a body that was announced longer than what was written). */
        void close() {
            if (closed) return;
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // gone already
            }
        }
    }

    /** Header names are case-insensitive. */
    static final class Headers {
        private final Map<String, String> map;

        Headers(Map<String, String> map) {
            this.map = map;
        }

        String getFirst(String name) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }

        void add(String name, String value) {
            map.put(name, value);
        }

        Set<String> keySet() {
            return map.keySet();
        }
    }

    private final ServerSocket socket;
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
    final List<String> ranges = Collections.synchronizedList(new ArrayList<String>());
    private volatile boolean stopped;

    FakeServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!stopped) {
                try {
                    Socket client = socket.accept();
                    Thread t = new Thread(() -> serve(client), "fake-server-client");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void serve(Socket client) {
        Exchange ex = null;
        try {
            client.setSoTimeout(20_000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String request = line(in);
            if (request == null || request.isEmpty()) return;
            Map<String, String> headers = new LinkedHashMap<>();
            for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                int colon = h.indexOf(':');
                if (colon > 0) headers.put(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
            }
            String target = request.split(" ")[1];
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            ex = new Exchange(client, path, headers);
            requests.add(path);
            String range = ex.getRequestHeaders().getFirst("Range");
            ranges.add(range == null ? "" : range);
            Handler h = routes.get(path);
            if (h == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                h.handle(ex);
            }
        } catch (IOException | RuntimeException e) {
            // a client that went away
        } finally {
            if (ex != null) ex.close();
            else {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // gone
                }
            }
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') out.write(c);
        }
        return c < 0 && out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    String base() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    int count(String path) {
        int n = 0;
        synchronized (requests) {
            for (String r : requests) if (r.equals(path)) n++;
        }
        return n;
    }

    void route(String path, Handler handler) {
        routes.put(path, handler);
    }

    void json(String path, String body) {
        route(path, ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        });
    }

    void status(String path, int code) {
        route(path, ex -> ex.sendResponseHeaders(code, -1));
    }

    /** A file with a working "Range: bytes=N-" (200, or 206 with Content-Range). */
    void file(String path, byte[] data) {
        route(path, ex -> sendRanged(ex, data, true));
    }

    /** A file that ignores Range and always answers 200 whole. */
    void fileIgnoringRange(String path, byte[] data) {
        route(path, ex -> sendRanged(ex, data, false));
    }

    /** The first request gets {@code cutAfter} bytes of the announced length and a closed connection; then it behaves. */
    void fileCutOnce(String path, byte[] data, int cutAfter) {
        boolean[] cut = {false};
        route(path, ex -> {
            if (!cut[0] && ex.getRequestHeaders().getFirst("Range") == null) {
                cut[0] = true;
                ex.sendResponseHeaders(200, data.length);
                OutputStream out = ex.getResponseBody();
                out.write(data, 0, cutAfter);
                out.flush();
                ex.close();
                return;
            }
            sendRanged(ex, data, true);
        });
    }

    void redirect(String path, String location) {
        route(path, ex -> {
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(302, -1);
        });
    }

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-");

    private static void sendRanged(Exchange ex, byte[] data, boolean honourRange) throws IOException {
        String range = ex.getRequestHeaders().getFirst("Range");
        Matcher m = range == null ? null : RANGE.matcher(range);
        if (honourRange && m != null && m.matches()) {
            int from = Integer.parseInt(m.group(1));
            if (from >= data.length) {
                ex.sendResponseHeaders(416, -1);
                return;
            }
            ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (data.length - 1) + "/" + data.length);
            ex.sendResponseHeaders(206, data.length - from);
            ex.getResponseBody().write(data, from, data.length - from);
            return;
        }
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    /** The test map: GitHub's addresses go to this server (the debug build's mapping, written out for the tests). */
    UrlMap map() {
        String base = base();
        int port = socket.getLocalPort();
        return new UrlMap() {
            @Override
            public String map(String url) {
                if (url.startsWith("https://api.github.com/")) return base + "/api/" + url.substring(23);
                if (url.startsWith("https://github.com/")) return base + "/github/" + url.substring(19);
                return url;
            }

            @Override
            public boolean isTestHost(URL url) {
                return url.getProtocol().equals("http") && url.getHost().equals("127.0.0.1") && url.getPort() == port;
            }
        };
    }

    @Override
    public void close() {
        stopped = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed already
        }
    }
}
