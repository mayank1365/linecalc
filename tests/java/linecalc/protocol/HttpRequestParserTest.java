package linecalc.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The framing tests. Every one of these is really the same question asked differently: after
 * parsing a request, is the stream positioned exactly on the first byte of the next one?
 */
class HttpRequestParserTest {

    private static InputStream stream(String wire) {
        return new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void parsesRequestLineAndQuery() throws Exception {
        HttpRequest req = HttpRequestParser.parse(
                stream("GET /add?a=2&b=3 HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        assertEquals("GET", req.method());
        assertEquals("/add", req.path());
        assertEquals("2", req.param("a"));
        assertEquals("3", req.param("b"));
        assertEquals("localhost", req.header("host"));
    }

    @Test
    void stopsOnTheLastBodyByteSoTheNextRequestIsIntact() throws Exception {
        // Two requests in one buffer, the first carrying a body. If the parser reads even one
        // byte too many, the second request line is corrupted and the second parse fails.
        InputStream in = stream(
                "POST /add HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello"
                        + "GET /mul?a=6&b=7 HTTP/1.1\r\nHost: h\r\n\r\n");

        HttpRequest first = HttpRequestParser.parse(in);
        assertEquals("POST", first.method());
        assertEquals("hello", first.bodyAsText());

        HttpRequest second = HttpRequestParser.parse(in);
        assertEquals("GET", second.method());
        assertEquals("/mul", second.path());
        assertEquals("7", second.param("b"));

        assertNull(HttpRequestParser.parse(in), "stream should now be exhausted");
    }

    @Test
    void readsPipelinedRequestsBackToBack() throws Exception {
        InputStream in = stream(
                "GET /add?a=1&b=1 HTTP/1.1\r\nHost: h\r\n\r\n"
                        + "GET /sub?a=9&b=4 HTTP/1.1\r\nHost: h\r\n\r\n"
                        + "GET /div?a=8&b=2 HTTP/1.1\r\nHost: h\r\n\r\n");
        assertEquals("/add", HttpRequestParser.parse(in).path());
        assertEquals("/sub", HttpRequestParser.parse(in).path());
        assertEquals("/div", HttpRequestParser.parse(in).path());
        assertNull(HttpRequestParser.parse(in));
    }

    @Test
    void returnsNullOnCleanCloseBetweenRequests() throws Exception {
        assertNull(HttpRequestParser.parse(stream("")));
    }

    @Test
    void requiresHostOnHttp11ButKeepsFramingIntact() {
        HttpException e = assertThrows(HttpException.class,
                () -> HttpRequestParser.parse(stream("GET /add?a=2&b=3 HTTP/1.1\r\n\r\n")));
        assertEquals(400, e.status());
        // The request was fully framed, so the connection can survive the 400.
        assertTrue(e.framingIntact());
    }

    @Test
    void rejectsDuplicateHost() {
        HttpException e = assertThrows(HttpException.class, () -> HttpRequestParser.parse(
                stream("GET /add HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n")));
        assertEquals(400, e.status());
    }

    @Test
    void decodesChunkedBodies() throws Exception {
        InputStream in = stream("POST /add HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
                + "GET /add?a=1&b=2 HTTP/1.1\r\nHost: h\r\n\r\n");
        assertEquals("hello world", HttpRequestParser.parse(in).bodyAsText());
        // And the chunked terminator left the stream aligned for the next request.
        assertEquals("/add", HttpRequestParser.parse(in).path());
    }

    @Test
    void rejectsContentLengthAndTransferEncodingTogether() {
        // Two conflicting answers to "where does this end" is a smuggling primitive.
        HttpException e = assertThrows(HttpException.class, () -> HttpRequestParser.parse(stream(
                "POST /add HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\nhello")));
        assertEquals(400, e.status());
    }

    @Test
    void rejectsMalformedContentLength() {
        assertThrows(HttpException.class, () -> HttpRequestParser.parse(
                stream("POST /add HTTP/1.1\r\nHost: h\r\nContent-Length: five\r\n\r\n")));
        assertThrows(HttpException.class, () -> HttpRequestParser.parse(
                stream("POST /add HTTP/1.1\r\nHost: h\r\nContent-Length: -1\r\n\r\n")));
    }

    @Test
    void rejectsTruncatedBody() {
        HttpException e = assertThrows(HttpException.class, () -> HttpRequestParser.parse(
                stream("POST /add HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\nshort")));
        assertEquals(400, e.status());
    }

    @Test
    void rejectsMalformedRequestLineAndHeaders() {
        assertThrows(HttpException.class, () -> HttpRequestParser.parse(stream("GET\r\n\r\n")));
        assertThrows(HttpException.class,
                () -> HttpRequestParser.parse(stream("GET / HTTP/1.1\r\nno-colon\r\n\r\n")));
        assertThrows(HttpException.class, () -> HttpRequestParser.parse(
                stream("GET / HTTP/1.1\r\nHost: h\r\n folded\r\n\r\n")));
    }

    @Test
    void readsConnectionCloseIntent() throws Exception {
        HttpRequest keepAlive = HttpRequestParser.parse(
                stream("GET /add HTTP/1.1\r\nHost: h\r\n\r\n"));
        assertEquals(false, keepAlive.wantsClose());

        HttpRequest closing = HttpRequestParser.parse(
                stream("GET /add HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"));
        assertTrue(closing.wantsClose());

        // HTTP/1.0 is the other way round: closing is the default.
        HttpRequest oldStyle = HttpRequestParser.parse(stream("GET /add HTTP/1.0\r\n\r\n"));
        assertTrue(oldStyle.wantsClose());
        HttpRequest oldStyleKeeping = HttpRequestParser.parse(
                stream("GET /add HTTP/1.0\r\nConnection: keep-alive\r\n\r\n"));
        assertEquals(false, oldStyleKeeping.wantsClose());
    }

    @Test
    void decodesPercentEscapes() throws Exception {
        HttpRequest req = HttpRequestParser.parse(
                stream("GET /add?a=%2D5&b=3 HTTP/1.1\r\nHost: h\r\n\r\n"));
        assertEquals("-5", req.param("a"));
    }
}
