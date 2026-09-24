package linecalc.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * These two methods are the load-bearing ones in the whole repository: every framing decision
 * in both protocols eventually becomes "read exactly n" or "discard exactly n".
 */
class BytesTest {

    @Test
    void readExactlyStopsOnTheNthByte() throws Exception {
        InputStream in = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5 });
        assertArrayEquals(new byte[] { 1, 2, 3 }, Bytes.readExactly(in, 3));
        // Byte 4 is untouched and belongs to whoever reads next.
        assertArrayEquals(new byte[] { 4, 5 }, Bytes.readExactly(in, 2));
        assertEquals(-1, in.read());
    }

    @Test
    void readExactlyHandlesAZeroLengthRead() throws Exception {
        InputStream in = new ByteArrayInputStream(new byte[] { 9 });
        assertArrayEquals(new byte[0], Bytes.readExactly(in, 0));
        assertEquals(9, in.read());
    }

    @Test
    void readExactlyKeepsPullingAcrossShortReads() throws Exception {
        // A socket is free to hand back one byte at a time; a single read() call is never a
        // guarantee of n bytes, which is the bug readExactly exists to prevent.
        InputStream dribble = new InputStream() {
            private int pos = 0;
            private final byte[] data = { 10, 20, 30, 40 };

            @Override
            public int read() {
                return pos < data.length ? data[pos++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= data.length) {
                    return -1;
                }
                b[off] = data[pos++];
                return 1;               // never more than one, however much was asked for
            }
        };
        assertArrayEquals(new byte[] { 10, 20, 30, 40 }, Bytes.readExactly(dribble, 4));
    }

    @Test
    void readExactlyThrowsRatherThanReturningAShortBuffer() {
        InputStream in = new ByteArrayInputStream(new byte[] { 1, 2 });
        EOFException e = assertThrows(EOFException.class, () -> Bytes.readExactly(in, 5));
        assertEquals("stream ended after 2 of 5 bytes", e.getMessage());
    }

    @Test
    void skipExactlyDiscardsExactlyNAndLeavesTheRest() throws Exception {
        InputStream in = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6 });
        Bytes.skipExactly(in, 4);
        assertArrayEquals(new byte[] { 5, 6 }, Bytes.readExactly(in, 2));
    }

    @Test
    void skipExactlyHandlesMoreThanOneSinkBuffer() throws Exception {
        // The sink is 8 KiB; skipping past it must loop rather than silently stop.
        byte[] data = new byte[20_000];
        data[19_999] = 42;
        InputStream in = new ByteArrayInputStream(data);
        Bytes.skipExactly(in, 19_999);
        assertEquals(42, in.read());
        assertEquals(-1, in.read());
    }

    @Test
    void skipExactlyThrowsIfTheStreamEndsEarly() {
        InputStream in = new ByteArrayInputStream(new byte[] { 1, 2 });
        assertThrows(EOFException.class, () -> Bytes.skipExactly(in, 10));
    }

    @Test
    void readsUnsignedIntegersWithoutSignExtension() {
        // 0xFF must be 255, not -1. Getting this wrong makes a large frame look negative.
        byte[] b = { (byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC };
        assertEquals(255, Bytes.u8(b, 0));
        assertEquals(0xFFFE, Bytes.u16(b, 0));
        assertEquals(0xFFFEFD, Bytes.u24(b, 0));
        assertEquals(0xFFFEFDFCL, Bytes.u32(b, 0));
    }

    @Test
    void roundTripsEveryWidthAtItsBoundaries() {
        byte[] buf = new byte[4];
        for (int v : new int[] { 0, 1, 0x7FFF, 0xFFFF }) {
            Bytes.putU16(buf, 0, v);
            assertEquals(v, Bytes.u16(buf, 0));
        }
        for (int v : new int[] { 0, 1, 0x7FFFFF, 0xFFFFFF }) {
            Bytes.putU24(buf, 0, v);
            assertEquals(v, Bytes.u24(buf, 0));
        }
        for (long v : new long[] { 0, 1, 0x7FFFFFFFL, 0xFFFFFFFFL }) {
            Bytes.putU32(buf, 0, v);
            assertEquals(v, Bytes.u32(buf, 0));
        }
    }

    @Test
    void writesBigEndian() {
        byte[] buf = new byte[3];
        Bytes.putU24(buf, 0, 0x010203);
        assertArrayEquals(new byte[] { 0x01, 0x02, 0x03 }, buf,
                "network byte order: most significant octet first");
    }

    @Test
    void honoursOffsets() {
        byte[] buf = new byte[6];
        Bytes.putU16(buf, 2, 0xBEEF);
        assertArrayEquals(new byte[] { 0, 0, (byte) 0xBE, (byte) 0xEF, 0, 0 }, buf);
        assertEquals(0xBEEF, Bytes.u16(buf, 2));
    }
}
