package linecalc.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One fully framed HTTP/1.1 request: everything up to and including its last body byte. */
public final class HttpRequest {

    private final String method;
    private final String target;
    private final String path;
    private final Map<String, String> query;
    private final String version;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    HttpRequest(String method, String target, String path, Map<String, String> query,
                String version, Map<String, List<String>> headers, byte[] body) {
        this.method = method;
        this.target = target;
        this.path = path;
        this.query = query;
        this.version = version;
        this.headers = headers;
        this.body = body;
    }

    public String method() {
        return method;
    }

    public String target() {
        return target;
    }

    public String path() {
        return path;
    }

    public String version() {
        return version;
    }

    public byte[] body() {
        return body;
    }

    public String param(String name) {
        return query.get(name);
    }

    public Map<String, String> query() {
        return query;
    }

    /** First value of a header, case-insensitively, or null. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Whether we must close after answering.
     *
     * <p>HTTP/1.1 keeps the connection open unless told otherwise; HTTP/1.0 closes unless it
     * explicitly asked to stay.
     */
    public boolean wantsClose() {
        String connection = header("Connection");
        boolean close = hasToken(connection, "close");
        boolean keepAlive = hasToken(connection, "keep-alive");
        if ("HTTP/1.0".equals(version)) {
            return !keepAlive;
        }
        return close;
    }

    private static boolean hasToken(String headerValue, String token) {
        if (headerValue == null) {
            return false;
        }
        for (String part : headerValue.split(",")) {
            if (part.trim().toLowerCase(Locale.ROOT).equals(token)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return method + " " + target + " " + version
                + " (" + body.length + " body byte" + (body.length == 1 ? "" : "s") + ")";
    }

    public String bodyAsText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
