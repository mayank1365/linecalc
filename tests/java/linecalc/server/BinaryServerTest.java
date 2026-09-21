package linecalc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import linecalc.protocol.Frame;
import linecalc.protocol.FrameCodec;
import linecalc.protocol.FrameType;
import linecalc.protocol.HeaderCodec;
import linecalc.protocol.HeaderField;
import linecalc.protocol.ProtocolException;

/** bserve over a real socket. */
class BinaryServerTest {

    @TempDir
    static Path root;
    private static BinaryServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        Files.writeString(root.resolve("index.html"), "<h1>hello</h1>");
        Files.writeString(root.resolve("data.txt"), "plain text");
        Files.writeString(root.resolve(".secret"), "should not be served");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("index.html"), "sub page");
        server = new BinaryServer(root, 0).start();
        port = server.port();
    }

    @AfterAll
    static void stopServer() throws IOException {
        server.close();
    }

    @Test
    void servesAFileOverOneConnection() throws Exception {
        try (Conn c = new Conn()) {
            Exchange r = c.get("/index.html", 1);
            assertEquals(200, r.status);
            assertEquals("<h1>hello</h1>", r.bodyText());
            assertEquals("text/html; charset=utf-8", r.header("content-type"));
            assertEquals("14", r.header("content-length"));
            assertEquals(Integer.parseInt(r.header("content-length")), r.body.length);
        }
    }

    @Test
    void keepsTheConnectionOpenAcrossManyRequests() throws Exception {
        try (Conn c = new Conn()) {
            assertEquals(200, c.get("/index.html", 1).status);
            assertEquals(200, c.get("/data.txt", 3).status);
            assertEquals(404, c.get("/missing.html", 5).status);
            assertEquals(200, c.get("/sub/", 7).status);
            // Still serving on the same socket after an error in the middle.
            Exchange last = c.get("/index.html", 9);
            assertEquals(200, last.status);
            assertTrue(c.socket.isConnected() && !c.socket.isClosed());
        }
    }

    /**
     * The rule the assignment says may not be skipped, end to end.
     */
    @Test
    void skipsUnknownFrameTypesAndKeepsServing() throws Exception {
        try (Conn c = new Conn()) {
            // A frame type from a hypothetical version 2, with a payload the server cannot
            // possibly interpret, and every undefined flag bit set for good measure.
            c.write(new Frame(0x7F, 0xFF, 2, "a version 2 said something".getBytes(StandardCharsets.UTF_8)));
            c.write(new Frame(0x42, 0x00, 0, new byte[0]));

            // The server stepped over both and is still listening for real work.
            Exchange r = c.get("/index.html", 1);
            assertEquals(200, r.status);
            assertEquals("<h1>hello</h1>", r.bodyText());
        }
    }

    @Test
    void answers404ForAMissingFile() throws Exception {
        try (Conn c = new Conn()) {
            assertEquals(404, c.get("/nope.html", 1).status);
        }
    }

    @Test
    void refusesToLeaveTheDocumentRoot() throws Exception {
        try (Conn c = new Conn()) {
            assertEquals(403, c.get("/../../etc/passwd", 1).status);
            assertEquals(403, c.get("/sub/../../outside", 3).status);
            // And the connection is fine afterwards.
            assertEquals(200, c.get("/index.html", 5).status);
        }
    }

    @Test
    void hidesDotfiles() throws Exception {
        try (Conn c = new Conn()) {
            assertEquals(404, c.get("/.secret", 1).status);
        }
    }

    @Test
    void answers400ForAMalformedHeaderBlockAndStaysOpen() throws Exception {
        try (Conn c = new Conn()) {
            // A header block claiming a 9-octet value it does not have. The frame itself was
            // well formed, so the server consumed exactly Length octets and is still aligned.
            c.write(Frame.of(FrameType.REQUEST, Frame.FLAG_END_MESSAGE, 1,
                    new byte[] { 0x01, 0x00, 0x09, 'h', 'i' }));
            assertEquals(400, c.readExchange(1).status);

            assertEquals(200, c.get("/index.html", 3).status);
        }
    }

    @Test
    void answers400WhenRequiredPseudoHeadersAreMissing() throws Exception {
        try (Conn c = new Conn()) {
            c.write(Frame.of(FrameType.REQUEST, Frame.FLAG_END_MESSAGE, 1,
                    HeaderCodec.encode(List.of(new HeaderField(":method", "GET")))));
            assertEquals(400, c.readExchange(1).status);

            // :method and :path present, host missing.
            c.write(Frame.of(FrameType.REQUEST, Frame.FLAG_END_MESSAGE, 3,
                    HeaderCodec.encode(List.of(
                            new HeaderField(":method", "GET"),
                            new HeaderField(":path", "/index.html")))));
            assertEquals(400, c.readExchange(3).status);
        }
    }

    @Test
    void answers400ForBadStreamIds() throws Exception {
        try (Conn c = new Conn()) {
            assertEquals(400, c.get("/index.html", 2).status, "even ids are server-initiated");
            assertEquals(400, c.get("/index.html", 0).status, "id 0 is the connection itself");
            assertEquals(200, c.get("/index.html", 7).status);
            assertEquals(400, c.get("/index.html", 5).status, "ids must strictly increase");
        }
    }

    @Test
    void answers400WhenRequestDoesNotSetEndMessage() throws Exception {
        try (Conn c = new Conn()) {
            c.write(Frame.of(FrameType.REQUEST, 0, 1, HeaderCodec.encode(List.of(
                    new HeaderField(":method", "GET"),
                    new HeaderField(":path", "/index.html"),
                    new HeaderField("host", "localhost")))));
            assertEquals(400, c.readExchange(1).status);
        }
    }

    @Test
    void answers405ForMethodsOtherThanGetAndHead() throws Exception {
        try (Conn c = new Conn()) {
            assertEquals(405, c.request("POST", "/index.html", 1).status);
            assertEquals(405, c.request("DELETE", "/index.html", 3).status);
            assertEquals(200, c.request("HEAD", "/index.html", 5).status);
        }
    }

    @Test
    void headSendsHeadersWithNoDataFrames() throws Exception {
        try (Conn c = new Conn()) {
            Exchange r = c.request("HEAD", "/index.html", 1);
            assertEquals(200, r.status);
            assertEquals("14", r.header("content-length"), "the length a GET would have had");
            assertEquals(0, r.body.length);
            assertEquals(0, r.dataFrames);
            assertEquals(200, c.get("/index.html", 3).status);
        }
    }

    @Test
    void answers400ForFramesOnlyAServerMaySend() throws Exception {
        try (Conn c = new Conn()) {
            c.write(Frame.of(FrameType.DATA, Frame.FLAG_END_MESSAGE, 1, new byte[] { 1 }));
            assertEquals(400, c.readExchange(1).status);
        }
    }

    @Test
    void answersPingWithAnAck() throws Exception {
        try (Conn c = new Conn()) {
            c.write(Frame.of(FrameType.PING, 0, 0, new byte[] { 1, 2, 3, 4 }));
            Frame pong = FrameCodec.read(c.in);
            assertEquals(FrameType.PING, pong.knownType());
            assertTrue(pong.hasFlag(Frame.FLAG_ACK));
            assertEquals(0, pong.streamId());
            assertEquals(4, pong.length());
            // An ACK must not be answered, or the two of us ping forever.
            c.write(Frame.of(FrameType.PING, Frame.FLAG_ACK, 0, new byte[0]));
            assertEquals(200, c.get("/index.html", 1).status);
        }
    }

    @Test
    void closesOnAWrongPreface() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read(),
                    "a peer speaking another protocol should be hung up on, not replied to");
        }
    }

    @Test
    void splitsLargeBodiesAcrossDataFramesAndFlagsOnlyTheLast() throws Exception {
        byte[] big = new byte[BinaryConnection.DATA_CHUNK * 2 + 17];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251);
        }
        Files.write(root.resolve("big.bin"), big);
        try (Conn c = new Conn()) {
            Exchange r = c.get("/big.bin", 1);
            assertEquals(200, r.status);
            assertEquals(3, r.dataFrames);
            assertEquals(big.length, r.body.length);
            assertTrue(java.util.Arrays.equals(big, r.body));
        }
    }

    // ---- test client ----------------------------------------------------------------

    private static final class Conn implements AutoCloseable {
        final Socket socket = new Socket("localhost", port);
        final InputStream in;
        final OutputStream out;

        Conn() throws IOException {
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
            out.write(FrameCodec.PREFACE);
            out.flush();
        }

        void write(Frame frame) throws IOException {
            FrameCodec.write(out, frame);
            out.flush();
        }

        Exchange get(String path, int streamId) throws Exception {
            return request("GET", path, streamId);
        }

        Exchange request(String method, String path, int streamId) throws Exception {
            write(Frame.of(FrameType.REQUEST, Frame.FLAG_END_MESSAGE, streamId,
                    HeaderCodec.encode(List.of(
                            new HeaderField(":method", method),
                            new HeaderField(":path", path),
                            new HeaderField("host", "localhost:" + port)))));
            return readExchange(streamId);
        }

        Exchange readExchange(int streamId) throws IOException, ProtocolException {
            List<HeaderField> headers = null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int dataFrames = 0;
            while (true) {
                Frame frame = FrameCodec.readKnown(in);
                assertNotNull(frame, "server closed mid-response");
                if (frame.knownType() == FrameType.RESPONSE) {
                    headers = HeaderCodec.decode(frame.payload());
                    if (frame.hasFlag(Frame.FLAG_END_MESSAGE)) {
                        break;
                    }
                } else if (frame.knownType() == FrameType.DATA) {
                    dataFrames++;
                    body.write(frame.payload());
                    if (frame.hasFlag(Frame.FLAG_END_MESSAGE)) {
                        break;
                    }
                }
            }
            assertNotNull(headers, "no RESPONSE frame arrived");
            int status = Integer.parseInt(HeaderField.find(headers, ":status"));
            return new Exchange(status, headers, body.toByteArray(), dataFrames);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private record Exchange(int status, List<HeaderField> headers, byte[] body, int dataFrames) {
        String header(String name) {
            return HeaderField.find(headers, name);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
