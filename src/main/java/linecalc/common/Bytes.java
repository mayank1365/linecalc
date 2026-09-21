package linecalc.common;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Byte-level helpers shared by both wire formats.
 *
 * <p>Everything here exists to serve one rule: read exactly the number of bytes the
 * framing says belong to this message, and not one more. Byte n+1 belongs to somebody else.
 */
public final class Bytes {

    private Bytes() {
    }

    /** Reads exactly {@code len} bytes, or throws {@link EOFException} if the peer stopped early. */
    public static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        readExactly(in, buf, 0, len);
        return buf;
    }

    public static void readExactly(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) {
                throw new EOFException("stream ended after " + read + " of " + len + " bytes");
            }
            read += n;
        }
    }

    /**
     * Discards exactly {@code len} bytes without buffering them.
     *
     * <p>This is how a receiver skips a frame whose type it does not understand: the length
     * prefix is trusted, the payload is thrown away, and the stream stays in sync for the
     * next header.
     */
    public static void skipExactly(InputStream in, long len) throws IOException {
        byte[] sink = new byte[(int) Math.min(len, 8192L)];
        long left = len;
        while (left > 0) {
            int n = in.read(sink, 0, (int) Math.min(left, sink.length));
            if (n < 0) {
                throw new EOFException("stream ended with " + left + " bytes left to skip");
            }
            left -= n;
        }
    }

    public static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    public static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }

    public static int u24(byte[] b, int off) {
        return (b[off] & 0xFF) << 16 | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF);
    }

    public static long u32(byte[] b, int off) {
        return (long) (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16
                | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
    }

    public static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    public static void putU24(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 16);
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) v;
    }

    public static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
