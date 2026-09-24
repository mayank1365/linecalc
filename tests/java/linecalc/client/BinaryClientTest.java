package linecalc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import linecalc.client.BinaryClient.Target;

/** URL parsing, which is the only part of bcurl that does not need a server. */
class BinaryClientTest {

    @Test
    void parsesHostPortAndPath() {
        Target t = Target.parse("localhost:9000/index.html");
        assertEquals("localhost", t.host());
        assertEquals(9000, t.port());
        assertEquals("/index.html", t.path());
        assertEquals("localhost:9000", t.authority());
    }

    @Test
    void defaultsThePortToTheServersDefault() {
        assertEquals(9000, Target.parse("example.com/a").port());
    }

    @Test
    void defaultsAMissingPathToRoot() {
        assertEquals("/", Target.parse("localhost:9000").path());
        assertEquals("/", Target.parse("localhost").path());
    }

    @Test
    void stripsASchemeIfOneIsGiven() {
        assertEquals("localhost", Target.parse("lcb://localhost:9000/a").host());
        assertEquals("localhost", Target.parse("http://localhost:9000/a").host());
        assertEquals("localhost", Target.parse("HTTP://localhost:9000/a").host());
        assertEquals("/a", Target.parse("lcb://localhost:9000/a").path());
    }

    @Test
    void keepsQueryStringsAndDeepPathsIntact() {
        assertEquals("/a/b/c.txt?x=1&y=2", Target.parse("h:1/a/b/c.txt?x=1&y=2").path());
    }

    @Test
    void comparesEndpointsRatherThanWholeUrls() {
        Target a = Target.parse("localhost:9000/one.html");
        Target b = Target.parse("localhost:9000/two.html");
        Target c = Target.parse("localhost:9001/one.html");
        Target d = Target.parse("example.com:9000/one.html");
        // Same socket: different paths are fine, a different endpoint is not.
        assertEquals(true, a.sameEndpoint(b));
        assertEquals(false, a.sameEndpoint(c));
        assertEquals(false, a.sameEndpoint(d));
    }

    @Test
    void rejectsUrlsItCannotUse() {
        assertThrows(IllegalArgumentException.class, () -> Target.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Target.parse("localhost:notanumber/a"));
        assertThrows(IllegalArgumentException.class, () -> Target.parse("localhost:0/a"));
        assertThrows(IllegalArgumentException.class, () -> Target.parse("localhost:70000/a"));
        assertThrows(IllegalArgumentException.class, () -> Target.parse(":9000/a"));
    }
}
