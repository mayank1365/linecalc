package linecalc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import linecalc.server.BinaryServer;

/**
 * The real client against the real server.
 *
 * <p>Every other test in the suite drives one side with a hand-built peer, which proves each
 * side matches my reading of the spec. This one proves the two agree with each other, which is
 * the only claim that matters to somebody writing a third implementation.
 */
class InteropTest {

    @TempDir
    static Path root;
    private static BinaryServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        Files.writeString(root.resolve("index.html"), "<h1>interop</h1>");
        Files.writeString(root.resolve("a.txt"), "first");
        Files.writeString(root.resolve("b.txt"), "second");
        Files.write(root.resolve("big.bin"), new byte[40_000]);
        server = new BinaryServer(root, 0).start();
        port = server.port();
    }

    @AfterAll
    static void stopServer() throws IOException {
        server.close();
    }

    /** Runs bcurl and captures what it wrote to stdout. */
    private static Result bcurl(boolean verbose, boolean head, String... urls) {
        PrintStream realOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true));
            int exit = new BinaryClient(verbose).run(List.of(urls), head);
            System.out.flush();
            return new Result(exit, captured.toByteArray());
        } finally {
            System.setOut(realOut);
        }
    }

    private static String url(String path) {
        return "localhost:" + port + path;
    }

    @Test
    void clientFetchesWhatTheServerServes() {
        Result r = bcurl(false, false, url("/index.html"));
        assertEquals(0, r.exit);
        assertEquals("<h1>interop</h1>", r.text());
    }

    @Test
    void severalUrlsTravelDownOneConnectionInOrder() {
        Result r = bcurl(false, false, url("/a.txt"), url("/b.txt"), url("/a.txt"));
        assertEquals(0, r.exit);
        // Bodies concatenated in request order, from a single socket on streams 1, 3 and 5.
        assertEquals("firstsecondfirst", r.text());
    }

    @Test
    void exitCodeDistinguishesClientErrorsFromSuccess() {
        assertEquals(0, bcurl(false, false, url("/a.txt")).exit);
        assertEquals(4, bcurl(false, false, url("/missing.txt")).exit, "404");
        assertEquals(4, bcurl(false, false, url("/../escape")).exit, "403");
    }

    @Test
    void theWorstStatusInABatchIsTheExitCode() {
        // One good, one missing: the failure must not be masked by the success.
        assertEquals(4, bcurl(false, false, url("/a.txt"), url("/missing.txt")).exit);
        assertEquals(4, bcurl(false, false, url("/missing.txt"), url("/a.txt")).exit);
    }

    @Test
    void refusesASecondEndpointRatherThanOpeningASecondConnection() {
        Result r = bcurl(false, false, url("/a.txt"), "localhost:1/b.txt");
        assertEquals(2, r.exit, "a usage error, not a second dial");
        assertEquals("", r.text(), "and nothing was fetched at all");
    }

    @Test
    void headFetchesNoBodyButStillSucceeds() {
        Result r = bcurl(false, true, url("/index.html"));
        assertEquals(0, r.exit);
        assertEquals("", r.text());
    }

    @Test
    void reassemblesABodySplitAcrossManyDataFrames() {
        Result r = bcurl(false, false, url("/big.bin"));
        assertEquals(0, r.exit);
        assertEquals(40_000, r.body.length, "three DATA frames, reassembled");
    }

    @Test
    void verboseOutputNeverContaminatesStdout() {
        Result quiet = bcurl(false, false, url("/a.txt"));
        Result loud = bcurl(true, false, url("/a.txt"));
        // -v adds a great deal of output, and every byte of it goes to stderr.
        assertEquals(quiet.text(), loud.text());
        assertEquals("first", loud.text());
    }

    @Test
    void reportsATransportFailureRatherThanThrowing() {
        int exit = new BinaryClient(false).run(List.of("localhost:1/nothing"), false);
        assertEquals(1, exit);
    }

    @Test
    void serverStaysUpForTheNextClientAfterOneDisconnects() {
        assertEquals(0, bcurl(false, false, url("/a.txt")).exit);
        assertEquals(4, bcurl(false, false, url("/missing.txt")).exit);
        // A fresh client, after an error on a previous connection.
        Result r = bcurl(false, false, url("/b.txt"));
        assertEquals(0, r.exit);
        assertEquals("second", r.text());
    }

    @Test
    void manyClientsAtOnceAreAllServedCorrectly() throws Exception {
        int clients = 12;
        Thread[] threads = new Thread[clients];
        boolean[] ok = new boolean[clients];
        for (int i = 0; i < clients; i++) {
            int n = i;
            threads[i] = new Thread(() -> {
                int exit = new BinaryClient(false).run(List.of(url("/a.txt")), false);
                ok[n] = exit == 0;
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join(15_000);
        }
        for (int i = 0; i < clients; i++) {
            assertTrue(ok[i], "client " + i + " failed");
        }
    }

    private record Result(int exit, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
