package linecalc.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class HeaderCodecTest {

    @Test
    void staticNamesCostOneOctet() {
        byte[] wire = HeaderCodec.encode(List.of(new HeaderField(":method", "GET")));
        assertArrayEquals(new byte[] {
                0x01,             // static index 1 == :method
                0x00, 0x03,       // value length 3
                'G', 'E', 'T' }, wire);
    }

    @Test
    void literalNamesCarryTheirOwnLengthPrefix() {
        byte[] wire = HeaderCodec.encode(List.of(new HeaderField("x-trace", "abc")));
        assertArrayEquals(new byte[] {
                0x00,                                      // literal name follows
                0x07, 'x', '-', 't', 'r', 'a', 'c', 'e',   // name
                0x00, 0x03, 'a', 'b', 'c' }, wire);
    }

    @Test
    void roundTripsAWholeBlock() throws Exception {
        List<HeaderField> sent = List.of(
                new HeaderField(":status", "200"),
                new HeaderField("content-type", "text/html; charset=utf-8"),
                new HeaderField("content-length", "941"),
                new HeaderField("x-custom", "kept as a literal"));
        List<HeaderField> got = HeaderCodec.decode(HeaderCodec.encode(sent));
        assertEquals(sent, got);
    }

    @Test
    void namesAreLowercasedOnTheWayIn() {
        assertEquals("content-type", new HeaderField("Content-Type", "text/plain").name());
    }

    @Test
    void handlesEmptyValuesAndEmptyBlocks() throws Exception {
        List<HeaderField> got = HeaderCodec.decode(
                HeaderCodec.encode(List.of(new HeaderField("connection", ""))));
        assertEquals(List.of(new HeaderField("connection", "")), got);
        assertEquals(List.of(), HeaderCodec.decode(new byte[0]));
    }

    @Test
    void handlesMultiByteUtf8Values() throws Exception {
        String value = "café — µs";
        List<HeaderField> got = HeaderCodec.decode(
                HeaderCodec.encode(List.of(new HeaderField("server", value))));
        assertEquals(value, HeaderField.find(got, "server"));
    }

    @Test
    void findsFieldsCaseInsensitively() throws Exception {
        List<HeaderField> fields = HeaderCodec.decode(
                HeaderCodec.encode(List.of(new HeaderField(":status", "404"))));
        assertEquals("404", HeaderField.find(fields, ":STATUS"));
        assertEquals(null, HeaderField.find(fields, "date"));
    }

    @Test
    void rejectsATruncatedValue() {
        // Claims a 9-octet value but supplies two.
        byte[] wire = { 0x01, 0x00, 0x09, 'h', 'i' };
        ProtocolException e = assertThrows(ProtocolException.class, () -> HeaderCodec.decode(wire));
        assertEquals(400, e.status());
        // The frame length already bounded the damage, so the connection survives.
        assertEquals(true, e.framingIntact());
    }

    @Test
    void rejectsATruncatedLiteralName() {
        assertThrows(ProtocolException.class,
                () -> HeaderCodec.decode(new byte[] { 0x00, 0x05, 'x', '-' }));
        assertThrows(ProtocolException.class, () -> HeaderCodec.decode(new byte[] { 0x00 }));
    }

    @Test
    void rejectsAnUndefinedStaticIndex() {
        // Unlike an unknown frame type this is not skippable: we would be dropping a header
        // whose meaning we cannot see, and a version 2 may only append to the table anyway.
        ProtocolException e = assertThrows(ProtocolException.class,
                () -> HeaderCodec.decode(new byte[] { 0x0B, 0x00, 0x01, 'x' }));
        assertEquals(400, e.status());
    }

    @Test
    void rejectsAnEmptyOrMalformedName() {
        assertThrows(ProtocolException.class,
                () -> HeaderCodec.decode(new byte[] { 0x00, 0x00, 0x00, 0x00 }));
        assertThrows(ProtocolException.class,
                () -> HeaderCodec.decode(new byte[] { 0x00, 0x03, 'A', 'B', 'C', 0x00, 0x00 }));
    }

    @Test
    void staticTableIsFrozenInTheOrderTheSpecPublishes() {
        assertEquals(10, StaticTable.NAMES.size());
        assertEquals(":method", StaticTable.name(1));
        assertEquals(":path", StaticTable.name(2));
        assertEquals(":status", StaticTable.name(3));
        assertEquals("host", StaticTable.name(4));
        assertEquals("content-length", StaticTable.name(5));
        assertEquals("content-type", StaticTable.name(6));
        assertEquals("user-agent", StaticTable.name(7));
        assertEquals("server", StaticTable.name(8));
        assertEquals("date", StaticTable.name(9));
        assertEquals("connection", StaticTable.name(10));
        assertEquals(null, StaticTable.name(11));
        assertEquals(StaticTable.LITERAL, StaticTable.indexOf("x-anything"));
    }

    @Test
    void indexingBeatsSpellingItOut() {
        // The whole point of the static table, measured.
        int indexed = HeaderCodec.encode(List.of(new HeaderField("content-length", "941"))).length;
        int literal = HeaderCodec.encode(List.of(new HeaderField("x-length-xxxx", "941"))).length;
        assertEquals(6, indexed);
        assertEquals(literal - 14, indexed);
    }

    @Test
    void rejectsValuesLongerThanTheLengthPrefix() {
        String huge = "x".repeat(HeaderCodec.MAX_VALUE_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> HeaderCodec.encode(List.of(new HeaderField("server", huge))));
    }

    @Test
    void decodesWhatTheReferenceServerActuallySends() throws Exception {
        // Captured from bserve; see docs/annotated-frame.md.
        byte[] block = {
                0x03, 0x00, 0x03, '2', '0', '0',
                0x06, 0x00, 0x0A, 't', 'e', 'x', 't', '/', 'p', 'l', 'a', 'i', 'n',
                0x05, 0x00, 0x02, '4', '2' };
        List<HeaderField> fields = HeaderCodec.decode(block);
        assertEquals("200", HeaderField.find(fields, ":status"));
        assertEquals("text/plain", HeaderField.find(fields, "content-type"));
        assertEquals("42", HeaderField.find(fields, "content-length"));
        assertArrayEquals(block, HeaderCodec.encode(fields));
    }
}
