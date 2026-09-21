package linecalc.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FrameCodecTest {

    private static InputStream bytes(byte[]... parts) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            all.writeBytes(p);
        }
        return new ByteArrayInputStream(all.toByteArray());
    }

    private static byte[] raw(int length, int type, int flags, int streamId, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.write(type);
        out.write(flags);
        out.write(streamId >>> 16);
        out.write(streamId >>> 8);
        out.write(streamId);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    @Test
    void headerIsExactlyEightOctets() {
        assertEquals(8, FrameCodec.HEADER_BYTES);
        Frame frame = Frame.of(FrameType.PING, 0, 0, new byte[0]);
        assertEquals(8, FrameCodec.encode(frame).length);
    }

    @Test
    void encodesTheFieldsWhereTheSpecSaysTheyGo() {
        byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
        byte[] wire = FrameCodec.encode(new Frame(0x01, 0x01, 0x0000FF, payload));
        assertArrayEquals(new byte[] {
                0x00, 0x00, 0x02,       // length 2
                0x01,                   // type REQUEST
                0x01,                   // flags END_MESSAGE
                0x00, 0x00, (byte) 0xFF,// R=0, stream 255
                'h', 'i' }, wire);
    }

    @Test
    void roundTripsThroughTheStream() throws Exception {
        Frame sent = Frame.of(FrameType.DATA, Frame.FLAG_END_MESSAGE, 7,
                "body bytes".getBytes(StandardCharsets.UTF_8));
        Frame got = FrameCodec.read(bytes(FrameCodec.encode(sent)));
        assertEquals(FrameType.DATA, got.knownType());
        assertEquals(7, got.streamId());
        assertTrue(got.hasFlag(Frame.FLAG_END_MESSAGE));
        assertEquals("body bytes", new String(got.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void returnsNullOnACleanCloseAtAFrameBoundary() throws Exception {
        assertNull(FrameCodec.read(bytes(new byte[0])));
    }

    /**
     * The rule the assignment says may not be skipped.
     */
    @Test
    void skipsAnUnknownFrameTypeCleanlyAndKeepsReading() throws Exception {
        byte[] mystery = raw(5, 0x7F, 0xFF, 9, "xxxxx".getBytes(StandardCharsets.UTF_8));
        Frame known = Frame.of(FrameType.DATA, Frame.FLAG_END_MESSAGE, 1,
                "after".getBytes(StandardCharsets.UTF_8));
        InputStream in = bytes(mystery, FrameCodec.encode(known));

        // read() hands the unknown frame over intact, so a caller can log it...
        Frame first = FrameCodec.read(in);
        assertFalse(first.isKnown());
        assertEquals(0x7F, first.type());
        assertEquals(5, first.length());
        assertEquals("UNKNOWN(0x7f)", first.typeName());

        // ...and the stream is still aligned on the next header.
        Frame second = FrameCodec.read(in);
        assertEquals(FrameType.DATA, second.knownType());
        assertEquals("after", new String(second.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void readKnownDropsUnknownFramesWithoutTheCallerSeeingThem() throws Exception {
        InputStream in = bytes(
                raw(3, 0x40, 0, 1, new byte[] { 1, 2, 3 }),
                raw(0, 0x7E, 0, 1, new byte[0]),
                raw(2, 0x55, 0, 1, new byte[] { 9, 9 }),
                FrameCodec.encode(Frame.of(FrameType.RESPONSE, 0, 3, new byte[] { 42 })));

        Frame frame = FrameCodec.readKnown(in);
        assertEquals(FrameType.RESPONSE, frame.knownType());
        assertEquals(3, frame.streamId());
        assertNull(FrameCodec.readKnown(in));
    }

    @Test
    void ignoresTheReservedBitRatherThanRejectingIt() throws Exception {
        // R set, stream id 1. A receiver must mask it off, not complain: a bit defined as
        // ignored is a bit version 2 can still use.
        byte[] wire = raw(0, FrameType.DATA.code(), Frame.FLAG_END_MESSAGE, 0x800001, new byte[0]);
        Frame frame = FrameCodec.read(bytes(wire));
        assertEquals(1, frame.streamId());
    }

    @Test
    void ignoresUndefinedFlagBits() throws Exception {
        byte[] wire = raw(0, FrameType.DATA.code(), 0xFF, 1, new byte[0]);
        Frame frame = FrameCodec.read(bytes(wire));
        assertTrue(frame.hasFlag(Frame.FLAG_END_MESSAGE));
    }

    @Test
    void skipsAnOversizeFrameButStaysInSync() throws Exception {
        int tooBig = FrameCodec.MAX_ACCEPTED_PAYLOAD + 1;
        byte[] oversize = raw(tooBig, FrameType.DATA.code(), 0, 1, new byte[tooBig]);
        Frame next = Frame.of(FrameType.RESPONSE, 0, 5, new byte[] { 7 });
        InputStream in = bytes(oversize, FrameCodec.encode(next));

        ProtocolException e = assertThrows(ProtocolException.class, () -> FrameCodec.read(in));
        assertEquals(400, e.status());
        // The length prefix was still good enough to resynchronise with.
        assertTrue(e.framingIntact());
        assertEquals(5, FrameCodec.read(in).streamId());
    }

    @Test
    void truncatedHeaderIsNotRecoverable() {
        ProtocolException e = assertThrows(ProtocolException.class,
                () -> FrameCodec.read(bytes(new byte[] { 0x00, 0x00, 0x05 })));
        assertFalse(e.framingIntact(), "half a header leaves no way to find the next one");
    }

    @Test
    void truncatedPayloadIsNotRecoverable() {
        byte[] claimsTen = raw(10, FrameType.DATA.code(), 0, 1, "abc".getBytes());
        ProtocolException e = assertThrows(ProtocolException.class,
                () -> FrameCodec.read(bytes(claimsTen)));
        assertFalse(e.framingIntact());
    }

    @Test
    void prefaceIsTheFourOctetsTheSpecSays() {
        assertArrayEquals("LCB1".getBytes(StandardCharsets.US_ASCII), FrameCodec.PREFACE);
    }
}
