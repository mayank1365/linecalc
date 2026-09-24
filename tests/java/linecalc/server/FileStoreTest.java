package linecalc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import linecalc.protocol.ProtocolException;

/**
 * Path containment, tested directly rather than through a socket.
 *
 * <p>Traversal is the oldest bug in static file serving and it keeps recurring because the
 * obvious defence — reject paths containing ".." — is defeated by encoding and by symlinks.
 * These tests go after the defence that actually holds: normalise, resolve, then ask whether
 * the result is still under the root.
 */
class FileStoreTest {

    @TempDir
    static Path root;
    @TempDir
    static Path outside;
    private static FileStore files;

    @BeforeAll
    static void layOutFiles() throws IOException {
        Files.writeString(root.resolve("index.html"), "root index");
        Files.writeString(root.resolve("page.txt"), "a page");
        Files.writeString(root.resolve(".env"), "SECRET=hunter2");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("index.html"), "sub index");
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"), "[core]");
        Files.writeString(outside.resolve("secrets.txt"), "not yours");
        files = new FileStore(root);
    }

    private static int statusOf(String path) {
        ProtocolException e = assertThrows(ProtocolException.class, () -> files.resolve(path));
        return e.status();
    }

    /**
     * FileStore resolves its root with toRealPath(), so expectations must be built from the
     * resolved root too -- on macOS /var is itself a symlink to /private/var.
     */
    private static Path expected(String... parts) {
        Path p = files.root();
        for (String part : parts) {
            p = p.resolve(part);
        }
        return p;
    }

    @Test
    void resolvesAFileInTheRoot() throws Exception {
        assertEquals(expected("page.txt"), files.resolve("/page.txt"));
    }

    @Test
    void mapsDirectoryPathsToIndexHtml() throws Exception {
        assertEquals(expected("index.html"), files.resolve("/"));
        assertEquals(expected("sub", "index.html"), files.resolve("/sub/"));
    }

    @Test
    void refusesToLeaveTheRootHoweverThePathIsSpelled() {
        assertEquals(403, statusOf("/../secrets.txt"));
        assertEquals(403, statusOf("/sub/../../secrets.txt"));
        assertEquals(403, statusOf("/./../../etc/passwd"));
        assertEquals(403, statusOf("/sub/./../../.."));
    }

    @Test
    void refusesASymlinkThatPointsOutOfTheRoot() throws Exception {
        // The case that defeats every "does the path contain ..?" check: the path is
        // perfectly innocent and the filesystem does the escaping for you.
        Path link = root.resolve("escape.txt");
        try {
            Files.createSymbolicLink(link, outside.resolve("secrets.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return;     // Filesystem will not do symlinks; nothing to assert.
        }
        assertTrue(Files.exists(link), "the link itself resolves for the OS");
        assertEquals(403, statusOf("/escape.txt"));
    }

    @Test
    void allowsASymlinkThatStaysInsideTheRoot() throws Exception {
        Path link = root.resolve("alias.txt");
        try {
            Files.createSymbolicLink(link, root.resolve("page.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertEquals(expected("alias.txt"), files.resolve("/alias.txt"),
                "containment is the rule, not a blanket ban on symlinks");
    }

    @Test
    void hidesDotfilesAtEveryDepth() {
        assertEquals(404, statusOf("/.env"));
        assertEquals(404, statusOf("/.git/config"));
    }

    @Test
    void rejectsPathsThatAreNotPaths() {
        assertEquals(400, statusOf("page.txt"));
        assertEquals(400, statusOf(""));
        assertEquals(400, statusOf(null));
        assertEquals(400, statusOf("/page\0.txt"));
    }

    @Test
    void missingFilesAre404() {
        assertEquals(404, statusOf("/nope.txt"));
        assertEquals(404, statusOf("/sub/nope.txt"));
    }

    @Test
    void aDirectoryWithNoIndexIs404NotAListing() throws Exception {
        Files.createDirectory(root.resolve("empty"));
        assertEquals(404, statusOf("/empty/"));
        assertEquals(404, statusOf("/empty"));
    }

    @Test
    void readsFileContents() throws Exception {
        assertEquals("a page", new String(files.read(files.resolve("/page.txt"))));
    }

    @Test
    void guessesContentTypeFromTheExtension() {
        assertEquals("text/html; charset=utf-8", FileStore.contentType(Path.of("a.html")));
        assertEquals("text/plain; charset=utf-8", FileStore.contentType(Path.of("a.txt")));
        assertEquals("image/png", FileStore.contentType(Path.of("a.PNG")), "case insensitive");
        assertEquals("application/octet-stream", FileStore.contentType(Path.of("a.unknown")));
        assertEquals("application/octet-stream", FileStore.contentType(Path.of("noextension")));
    }

    @Test
    void refusesARootThatIsNotADirectory() throws Exception {
        Path file = root.resolve("page.txt");
        assertThrows(IOException.class, () -> new FileStore(file));
        assertThrows(IOException.class, () -> new FileStore(root.resolve("does-not-exist")));
    }
}
