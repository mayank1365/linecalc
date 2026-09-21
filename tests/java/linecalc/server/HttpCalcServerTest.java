package linecalc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests over a real socket.
 *
 * <p>The important assertion in this file is not any single status code. It is that one
 * {@link Socket}, opened once, answers every request in the assignment and is still usable
 * afterwards.
 */
class HttpCalcServerTest {

    private static HttpCalcServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        server = new HttpCalcServer(0).start();
        port = server.port();
    }

    @AfterAll
    static void stopServer() throws IOException {
        server.close();
    }

    @Test
    void oneSocketServesEveryRequestInTheAssignment() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            Conn c = new Conn(socket);

            assertResponse(c.exchange("GET", "/add?a=2&b=3"), 200, "5");
            assertResponse(c.exchange("GET", "/sub?a=10&b=4"), 200, "6");
            assertResponse(c.exchange("GET", "/mul?a=6&b=7"), 200, "42");
            assertResponse(c.exchange("GET", "/div?a=9&b=3"), 200, "3");
            assertEquals(400, c.exchange("GET", "/div?a=1&b=0").status);
            assertEquals(400, c.exchange("GET", "/add?a=x&b=3").status);
            assertEquals(404, c.exchange("GET", "/pow?a=2&b=8").status);
            assertEquals(405, c.exchange("POST", "/add").status);

            // Still alive after all eight.
            assertResponse(c.exchange("GET", "/add?a=20&b=22"), 200, "42");
            assertTrue(socket.isConnected() && !socket.isClosed());
        }
    }

    @Test
    void answersPipelinedRequestsInOrderOnOneSocket() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            StringBuilder all = new StringBuilder();
            for (String target : new String[] {
                    "/add?a=2&b=3", "/sub?a=10&b=4", "/mul?a=6&b=7",
                    "/div?a=9&b=3", "/div?a=1&b=0", "/pow?a=2&b=8" }) {
                all.append("GET ").append(target).append(" HTTP/1.1\r\nHost: localhost\r\n\r\n");
            }
            // All six go out before we read a single byte back.
            out.write(all.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            Conn c = new Conn(socket);
            assertResponse(c.readResponse(), 200, "5");
            assertResponse(c.readResponse(), 200, "6");
            assertResponse(c.readResponse(), 200, "42");
            assertResponse(c.readResponse(), 200, "3");
            assertEquals(400, c.readResponse().status);
            assertEquals(404, c.readResponse().status);
        }
    }

    @Test
    void missingHostIs400ButTheConnectionSurvives() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            Conn c = new Conn(socket);
            c.send("GET /add?a=2&b=3 HTTP/1.1\r\n\r\n");
            assertEquals(400, c.readResponse().status);
            // Framing was never in doubt, so the next request on the same socket still works.
            assertResponse(c.exchange("GET", "/add?a=2&b=3"), 200, "5");
        }
    }

    @Test
    void postBodyIsDrainedSoTheNextRequestIsNotCorrupted() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            Conn c = new Conn(socket);
            c.send("POST /add HTTP/1.1\r\nHost: localhost\r\nContent-Length: 11\r\n\r\nhello world");
            assertEquals(405, c.readResponse().status);
            // If the 11 body bytes had not been consumed, this request would be read as garbage.
            assertResponse(c.exchange("GET", "/mul?a=6&b=7"), 200, "42");
        }
    }

    @Test
    void everyResponseCarriesContentLength() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            Conn c = new Conn(socket);
            for (String target : new String[] { "/add?a=2&b=3", "/div?a=1&b=0", "/pow?a=1&b=1" }) {
                Response r = c.exchange("GET", target);
                assertNotNull(r.headers.get("content-length"),
                        target + " must be self-delimiting on a persistent connection");
                assertEquals(r.body.length(), Integer.parseInt(r.headers.get("content-length")));
            }
        }
    }

    @Test
    void honoursConnectionClose() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            Conn c = new Conn(socket);
            c.send("GET /add?a=2&b=3 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            Response r = c.readResponse();
            assertEquals(200, r.status);
            assertEquals("close", r.headers.get("connection"));
            assertEquals(-1, c.in.read(), "server should have closed after Connection: close");
        }
    }

    @Test
    void methodIsCheckedAfterPathSoUnknownPathsAre404() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            Conn c = new Conn(socket);
            assertEquals(404, c.exchange("POST", "/pow").status);
            assertEquals(405, c.exchange("POST", "/add").status);
            assertEquals("GET, HEAD", c.exchange("PUT", "/add").headers.get("allow"));
        }
    }

    @Test
    void headReturnsTheHeadersOfAGetWithNoBody() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            Conn c = new Conn(socket);
            Response r = c.exchange("HEAD", "/mul?a=6&b=7");
            assertEquals(200, r.status);
            assertEquals("2", r.headers.get("content-length"));
            assertEquals("", r.body);
            // And the connection is still framed correctly.
            assertResponse(c.exchange("GET", "/add?a=2&b=3"), 200, "5");
        }
    }

    private static void assertResponse(Response r, int status, String body) {
        assertEquals(status, r.status);
        assertEquals(body, r.body);
    }

    /** A tiny client that consumes exactly Content-Length bytes per response. */
    private static final class Conn {
        final InputStream in;
        final OutputStream out;

        Conn(Socket socket) throws IOException {
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void send(String wire) throws IOException {
            out.write(wire.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        Response exchange(String method, String target) throws IOException {
            send(method + " " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
            // A HEAD response declares the Content-Length a GET would have produced but sends
            // no body, so the reader has to be told not to go looking for one.
            return readResponse(!method.equals("HEAD"));
        }

        Response readResponse() throws IOException {
            return readResponse(true);
        }

        Response readResponse(boolean hasBody) throws IOException {
            String statusLine = readLine();
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while (!(line = readLine()).isEmpty()) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).toLowerCase(), line.substring(colon + 1).trim());
            }
            int length = hasBody ? Integer.parseInt(headers.getOrDefault("content-length", "0")) : 0;
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new Response(status, headers, new String(body, StandardCharsets.UTF_8));
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    buf.write(b);
                }
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private record Response(int status, Map<String, String> headers, String body) {
    }
}
