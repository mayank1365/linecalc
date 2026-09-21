package linecalc.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import linecalc.common.Bytes;

/**
 * Reads and writes the fixed-size frame header.
 *
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +---------------------------------------------------------------+
 *  |                  Payload Length (24)          |    Type (8)   |
 *  +---------------------------------------------------------------+
 *  |    Flags (8)  |R|              Stream ID (23)                 |
 *  +---------------------------------------------------------------+
 *  |                     Payload (Length octets)                 ...
 * </pre>
 *
 * <h2>Why these widths</h2>
 *
 * HTTP/2 chose 24/8/8/1+31, which is nine octets. Nine is an awkward number: every frame
 * header after the first straddles an eight-byte boundary, and the struct never sits flush in
 * a register or a cache line. We keep HTTP/2's first three fields unchanged and take 23 bits
 * of stream id instead of 31, which buys a header of exactly eight octets — one aligned read.
 *
 * <ul>
 *   <li><b>Length, 24 bits.</b> Copied from HTTP/2 for the same reason HTTP/2 picked it: 16
 *       bits caps a frame at 64 KiB, which forces a large response to be split into thousands
 *       of frames, and 32 bits lets a peer announce a 4 GiB allocation in the first four bytes
 *       it ever sends you. 24 bits is the compromise. We then apply a policy cap far below the
 *       structural one (see {@link #MAX_ACCEPTED_PAYLOAD}).
 *   <li><b>Type, 8 bits.</b> Four are used. The other 252 are the version-2 budget, and the
 *       skip rule below is what makes that budget real.
 *   <li><b>Flags, 8 bits.</b> One is used (END_MESSAGE, reused as ACK on PING). Flags are
 *       per-frame booleans that would otherwise each cost a payload byte and a parse step.
 *   <li><b>R, 1 bit.</b> Reserved, MUST be sent as 0 and MUST be ignored on receipt. A bit you
 *       have defined as "ignore me" is a bit you can later define as something else; a bit you
 *       validated strictly is a bit you can never use, because every old peer rejects it.
 *   <li><b>Stream ID, 23 bits.</b> 8.4 million concurrent requests on one connection. HTTP/2's
 *       31 bits exist because ids are never reused and a long-lived connection can exhaust
 *       them; at 23 bits a client that opens 1000 requests a second exhausts the space in
 *       about two and a half hours, at which point it opens a new connection. That is an
 *       acceptable trade for the aligned header.
 * </ul>
 *
 * <h2>The rule that matters</h2>
 *
 * A receiver that meets a type it does not know MUST discard exactly {@code Length} octets and
 * carry on. It must not close, must not error, must not guess. {@link #read} implements this
 * by returning unknown frames to the caller intact so they can be logged and dropped, and
 * {@link #readKnown} implements it by skipping them silently.
 */
public final class FrameCodec {

    /** Every frame header is exactly this long. */
    public static final int HEADER_BYTES = 8;

    /** The largest payload the 24-bit length field can describe. */
    public static final int MAX_STRUCTURAL_PAYLOAD = (1 << 24) - 1;

    /**
     * The largest payload this version will accept.
     *
     * <p>Structurally a peer may announce 16 MiB. Accepting that on the strength of three
     * bytes from a stranger is an invitation to allocate 16 MiB per connection, so version 1
     * caps frames at 64 KiB and senders split larger bodies across DATA frames. A frame over
     * the cap is skipped — the length prefix is still trusted enough to resynchronise — and
     * answered with 400.
     */
    public static final int MAX_ACCEPTED_PAYLOAD = 1 << 16;

    /** Sent once by the client before its first frame, so a wrong protocol fails immediately. */
    public static final byte[] PREFACE = { 'L', 'C', 'B', '1' };

    private FrameCodec() {
    }

    /** Serialises a frame to its exact wire bytes. */
    public static byte[] encode(Frame frame) {
        byte[] payload = frame.payload();
        byte[] out = new byte[HEADER_BYTES + payload.length];
        Bytes.putU24(out, 0, payload.length);
        out[3] = (byte) frame.type();
        out[4] = (byte) frame.flags();
        // Top bit of byte 5 is R and is always written as 0.
        Bytes.putU24(out, 5, frame.streamId() & 0x7FFFFF);
        System.arraycopy(payload, 0, out, HEADER_BYTES, payload.length);
        return out;
    }

    public static void write(OutputStream out, Frame frame) throws IOException {
        out.write(encode(frame));
    }

    /**
     * Reads the next frame, known or not.
     *
     * @return the frame, or null if the peer closed cleanly on a frame boundary
     * @throws ProtocolException if the frame is malformed. If the length prefix was readable
     *                           the payload has already been skipped, so the caller may answer
     *                           400 and keep reading.
     */
    public static Frame read(InputStream in) throws IOException, ProtocolException {
        byte[] header = new byte[HEADER_BYTES];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        header[0] = (byte) first;
        try {
            Bytes.readExactly(in, header, 1, HEADER_BYTES - 1);
        } catch (EOFException e) {
            // We have part of a header and no way to find the next one.
            throw new ProtocolException(Status.BAD_REQUEST, "truncated frame header", false);
        }

        int length = Bytes.u24(header, 0);
        int type = Bytes.u8(header, 3);
        int flags = Bytes.u8(header, 4);
        // Mask off R rather than checking it: reserved bits MUST be ignored on receipt.
        int streamId = Bytes.u24(header, 5) & 0x7FFFFF;

        if (length > MAX_ACCEPTED_PAYLOAD) {
            Bytes.skipExactly(in, length);
            throw new ProtocolException(Status.BAD_REQUEST,
                    "frame payload of " + length + " bytes exceeds the " + MAX_ACCEPTED_PAYLOAD
                            + " byte limit");
        }

        byte[] payload;
        try {
            payload = Bytes.readExactly(in, length);
        } catch (EOFException e) {
            throw new ProtocolException(Status.BAD_REQUEST, "truncated frame payload", false);
        }
        return new Frame(type, flags, streamId, payload);
    }

    /**
     * Reads the next frame this version understands, silently discarding any it does not.
     *
     * <p>This is the skip rule in its simplest form, for callers that do not want to see
     * unknown frames at all.
     */
    public static Frame readKnown(InputStream in) throws IOException, ProtocolException {
        while (true) {
            Frame frame = read(in);
            if (frame == null || frame.isKnown()) {
                return frame;
            }
        }
    }
}
