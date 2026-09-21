package linecalc.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One HTTP/1.1 response.
 *
 * <p>Every response this server produces carries a {@code Content-Length}. That is not
 * politeness — on a connection that stays open it is the only thing telling the client where
 * our bytes stop and where it may start reading the answer to its next request.
 */
public final class HttpResponse {

    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").withZone(ZoneOffset.UTC);

    private final int status;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private boolean omitBody;

    public HttpResponse(int status) {
        this.status = status;
        headers.put("Date", IMF_FIXDATE.format(ZonedDateTime.now(ZoneOffset.UTC)));
        headers.put("Server", "linecalc/1.0");
    }

    public static HttpResponse text(int status, String body) {
        HttpResponse res = new HttpResponse(status);
        res.header("Content-Type", "text/plain; charset=utf-8");
        res.body(body.getBytes(StandardCharsets.UTF_8));
        return res;
    }

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse body(byte[] body) {
        this.body = body;
        return this;
    }

    /** For HEAD: keep the headers a GET would produce, send none of the body. */
    public HttpResponse omitBody() {
        this.omitBody = true;
        return this;
    }

    public int status() {
        return status;
    }

    public byte[] bodyBytes() {
        return body;
    }

    public void writeTo(OutputStream out) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(Status.reason(status)).append("\r\n");
        headers.put("Content-Length", Integer.toString(body.length));
        for (Map.Entry<String, String> h : headers.entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!omitBody) {
            out.write(body);
        }
    }
}
