package linecalc.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HttpResponseTest {

    private static String write(HttpResponse response) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.writeTo(out);
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void writesAStatusLineHeadersABlankLineAndTheBody() throws Exception {
        String wire = write(HttpResponse.text(200, "5"));
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), wire);
        assertTrue(wire.endsWith("\r\n\r\n5"), "blank line, then the body, and nothing after it");
    }

    @Test
    void alwaysSetsContentLengthItself() throws Exception {
        // Nothing is self-delimiting on a persistent connection without this, so it is
        // computed rather than trusted to a caller.
        assertTrue(write(HttpResponse.text(200, "42")).contains("Content-Length: 2\r\n"));
        assertTrue(write(HttpResponse.text(200, "")).contains("Content-Length: 0\r\n"));
    }

    @Test
    void countsOctetsNotCharacters() throws Exception {
        // "é" is two octets in UTF-8. A length in characters would leave the client waiting
        // for a byte that never comes, or eating the next response's first byte.
        String wire = write(HttpResponse.text(200, "é"));
        assertTrue(wire.contains("Content-Length: 2\r\n"), wire);
    }

    @Test
    void usesCrlfLineEndingsThroughout() throws Exception {
        String wire = write(HttpResponse.text(404, "gone"));
        assertFalse(wire.replace("\r\n", "").contains("\n"), "no bare LF anywhere in the head");
    }

    @Test
    void carriesDateAndServerByDefault() throws Exception {
        String wire = write(new HttpResponse(200));
        assertTrue(wire.contains("Server: linecalc/1.0\r\n"));
        assertTrue(wire.contains("Date: "));
        assertTrue(wire.matches("(?s).*Date: \\w{3}, \\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT.*"),
                "IMF-fixdate, as RFC 9110 requires");
    }

    @Test
    void headerValuesCanBeOverridden() throws Exception {
        String wire = write(new HttpResponse(200).header("Server", "custom/9"));
        assertTrue(wire.contains("Server: custom/9\r\n"));
        assertFalse(wire.contains("linecalc/1.0"));
    }

    @Test
    void omitBodyKeepsTheHeadersAGetWouldHaveSent() throws Exception {
        HttpResponse response = HttpResponse.text(200, "42").omitBody();
        String wire = write(response);
        assertTrue(wire.contains("Content-Length: 2\r\n"), "the length a GET would have had");
        assertTrue(wire.endsWith("\r\n\r\n"), "but not one octet of body");
    }

    @Test
    void reportsItsOwnStatusAndBody() {
        HttpResponse response = HttpResponse.text(405, "nope");
        assertEquals(405, response.status());
        assertEquals("nope", new String(response.bodyBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void knowsTheReasonPhrasesItUses() {
        assertEquals("OK", Status.reason(200));
        assertEquals("Bad Request", Status.reason(400));
        assertEquals("Forbidden", Status.reason(403));
        assertEquals("Not Found", Status.reason(404));
        assertEquals("Method Not Allowed", Status.reason(405));
        assertEquals("Internal Server Error", Status.reason(500));
    }

    @Test
    void classifiesStatusRanges() {
        assertTrue(Status.isClientError(404));
        assertFalse(Status.isClientError(500));
        assertFalse(Status.isClientError(200));
        assertTrue(Status.isServerError(500));
        assertFalse(Status.isServerError(404));
    }
}
