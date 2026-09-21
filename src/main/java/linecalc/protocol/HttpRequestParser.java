package linecalc.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import linecalc.common.Bytes;

/**
 * Reads one HTTP/1.1 request off a stream and stops on its last byte.
 *
 * <p>This is the whole difficulty of keep-alive. With HTTP/1.0 the answer to "where does this
 * request end?" was "at EOF", handed to you for free by the close. Once the connection stays
 * open you have to derive the boundary yourself: the blank line ends the head, and then either
 * {@code Content-Length} or the chunked terminator ends the body. Read one byte past that and
 * you have eaten the first byte of the next request.
 *
 * <p>The parser therefore never reads ahead. It is handed the connection's single long-lived
 * {@link InputStream} and consumes exactly what the framing describes.
 */
public final class HttpRequestParser {

    /** Longest single line (request line or header) we will accept. */
    public static final int MAX_LINE_BYTES = 8192;
    /** Most header fields we will accept in one request. */
    public static final int MAX_HEADER_FIELDS = 100;
    /** Largest request body we will buffer. */
    public static final int MAX_BODY_BYTES = 1 << 20;

    private HttpRequestParser() {
    }

    /**
     * @return the next request, or null if the peer closed the connection cleanly between
     *         requests (which is normal and not an error)
     */
    public static HttpRequest parse(InputStream in) throws IOException, HttpException {
        String requestLine = readLine(in, true);
        if (requestLine == null) {
            return null;
        }
        // Tolerate leading blank lines left over by sloppy clients (RFC 9112 section 2.2).
        while (requestLine.isEmpty()) {
            requestLine = readLine(in, true);
            if (requestLine == null) {
                return null;
            }
        }

        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            throw new HttpException(400, "malformed request line");
        }
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];
        if (method.isEmpty() || !isToken(method)) {
            throw new HttpException(400, "malformed method");
        }
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new HttpException(505, "unsupported HTTP version: " + version);
        }

        Map<String, List<String>> headers = readHeaders(in);

        // HTTP/1.1 requires a Host header. This is the one rule keep-alive did not invent but
        // that a 1.0-shaped server tends to forget.
        List<String> host = headers.get("Host");
        if (version.equals("HTTP/1.1")) {
            if (host == null || host.isEmpty()) {
                throw new HttpException(400, "missing Host header", true);
            }
            if (host.size() > 1) {
                throw new HttpException(400, "duplicate Host header", true);
            }
        }

        byte[] body = readBody(in, headers);

        String path = target;
        Map<String, String> query = new LinkedHashMap<>();
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            parseQuery(target.substring(q + 1), query);
        }
        if (!path.startsWith("/")) {
            // We do not serve absolute-form or authority-form targets.
            throw new HttpException(400, "request target must be an absolute path", true);
        }
        path = percentDecode(path, false);

        return new HttpRequest(method, target, path, query, version, headers, body);
    }

    private static Map<String, List<String>> readHeaders(InputStream in)
            throws IOException, HttpException {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int count = 0;
        while (true) {
            String line = readLine(in, false);
            if (line == null) {
                throw new HttpException(400, "connection ended inside the header block");
            }
            if (line.isEmpty()) {
                return headers;
            }
            if (++count > MAX_HEADER_FIELDS) {
                throw new HttpException(431, "too many header fields");
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                // Obsolete line folding. RFC 9112 says a server MUST reject it or replace it
                // with spaces; rejecting is safer and simpler.
                throw new HttpException(400, "obsolete header line folding");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(400, "malformed header field");
            }
            String name = line.substring(0, colon);
            if (!isToken(name)) {
                throw new HttpException(400, "malformed header name");
            }
            String value = line.substring(colon + 1).strip();
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    /**
     * Consumes exactly the body the head described — no more, no less.
     */
    private static byte[] readBody(InputStream in, Map<String, List<String>> headers)
            throws IOException, HttpException {
        List<String> transferEncoding = headers.get("Transfer-Encoding");
        List<String> contentLength = headers.get("Content-Length");

        if (transferEncoding != null && contentLength != null) {
            // Both present means two different answers to "where does this end?". That is a
            // request-smuggling primitive, not an ambiguity to resolve.
            throw new HttpException(400, "both Transfer-Encoding and Content-Length present");
        }

        if (transferEncoding != null) {
            String encoding = String.join(",", transferEncoding).trim().toLowerCase(Locale.ROOT);
            if (!encoding.equals("chunked")) {
                throw new HttpException(501, "unsupported Transfer-Encoding: " + encoding);
            }
            return readChunked(in);
        }

        if (contentLength == null) {
            return new byte[0];
        }
        if (contentLength.size() > 1) {
            throw new HttpException(400, "duplicate Content-Length header");
        }
        String raw = contentLength.get(0);
        long length;
        try {
            length = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new HttpException(400, "malformed Content-Length: " + raw);
        }
        if (length < 0) {
            throw new HttpException(400, "negative Content-Length");
        }
        if (length > MAX_BODY_BYTES) {
            throw new HttpException(413, "request body larger than " + MAX_BODY_BYTES + " bytes");
        }
        try {
            return Bytes.readExactly(in, (int) length);
        } catch (EOFException e) {
            throw new HttpException(400, "connection ended before Content-Length was satisfied");
        }
    }

    private static byte[] readChunked(InputStream in) throws IOException, HttpException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in, false);
            if (sizeLine == null) {
                throw new HttpException(400, "connection ended inside a chunked body");
            }
            int semicolon = sizeLine.indexOf(';');       // chunk extensions, which we ignore
            String sizeText = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).strip();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new HttpException(400, "malformed chunk size: " + sizeText);
            }
            if (size < 0 || body.size() + size > MAX_BODY_BYTES) {
                throw new HttpException(413, "chunked body too large");
            }
            if (size == 0) {
                // Trailer section, terminated by a blank line.
                String trailer;
                while ((trailer = readLine(in, false)) != null && !trailer.isEmpty()) {
                    // Trailers are consumed so the stream stays aligned, then discarded.
                }
                return body.toByteArray();
            }
            try {
                body.write(Bytes.readExactly(in, size));
            } catch (EOFException e) {
                throw new HttpException(400, "connection ended inside a chunk");
            }
            String crlf = readLine(in, false);
            if (crlf == null || !crlf.isEmpty()) {
                throw new HttpException(400, "chunk not terminated by CRLF");
            }
        }
    }

    /**
     * Reads one CRLF-terminated line as ISO-8859-1, one byte at a time.
     *
     * <p>One byte at a time is deliberate: it is the only read pattern that cannot
     * accidentally pull bytes belonging to the body or to the next pipelined request into a
     * private buffer. The stream handed in is buffered, so this stays cheap.
     *
     * @param atMessageStart when true, a clean EOF returns null instead of throwing
     */
    private static String readLine(InputStream in, boolean atMessageStart)
            throws IOException, HttpException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        boolean sawCr = false;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (atMessageStart && line.size() == 0) {
                    return null;
                }
                throw new HttpException(400, "connection ended mid-line");
            }
            if (sawCr) {
                if (b == '\n') {
                    return line.toString(StandardCharsets.ISO_8859_1);
                }
                throw new HttpException(400, "bare CR in message head");
            }
            if (b == '\r') {
                sawCr = true;
                continue;
            }
            if (b == '\n') {
                // Bare LF. RFC 9112 allows a server to accept it as a line terminator.
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            if (line.size() >= MAX_LINE_BYTES) {
                throw new HttpException(431, "line longer than " + MAX_LINE_BYTES + " bytes");
            }
            line.write(b);
        }
    }

    private static void parseQuery(String queryString, Map<String, String> into)
            throws HttpException {
        if (queryString.isEmpty()) {
            return;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            // First value wins, so a repeated parameter cannot quietly override the first.
            into.putIfAbsent(percentDecode(name, true), percentDecode(value, true));
        }
    }

    private static String percentDecode(String raw, boolean plusIsSpace) throws HttpException {
        if (raw.indexOf('%') < 0 && !(plusIsSpace && raw.indexOf('+') >= 0)) {
            return raw;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '+' && plusIsSpace) {
                out.write(' ');
            } else if (c == '%') {
                if (i + 2 >= raw.length()) {
                    throw new HttpException(400, "truncated percent-escape", true);
                }
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new HttpException(400, "malformed percent-escape", true);
                }
                out.write(hi << 4 | lo);
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isToken(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
