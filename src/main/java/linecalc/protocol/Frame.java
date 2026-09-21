package linecalc.protocol;

/**
 * One frame: an 8-byte header and its payload.
 *
 * <p>{@code type} is an int rather than a {@link FrameType} on purpose. A frame we do not
 * recognise is still a perfectly well-formed frame, and we need to be able to hold it, name it
 * in a log line, and skip it.
 */
public final class Frame {

    /** Last frame of this message. */
    public static final int FLAG_END_MESSAGE = 0x01;
    /** On a PING: this is the echo, not a new probe. Same bit, different frame type. */
    public static final int FLAG_ACK = 0x01;

    private final int type;
    private final int flags;
    private final int streamId;
    private final byte[] payload;

    public Frame(int type, int flags, int streamId, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.streamId = streamId;
        this.payload = payload;
    }

    public static Frame of(FrameType type, int flags, int streamId, byte[] payload) {
        return new Frame(type.code(), flags, streamId, payload);
    }

    public int type() {
        return type;
    }

    public FrameType knownType() {
        return FrameType.of(type);
    }

    public boolean isKnown() {
        return knownType() != null;
    }

    public int flags() {
        return flags;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public int streamId() {
        return streamId;
    }

    public byte[] payload() {
        return payload;
    }

    public int length() {
        return payload.length;
    }

    public String typeName() {
        FrameType known = knownType();
        return known != null ? known.name() : String.format("UNKNOWN(0x%02x)", type);
    }

    @Override
    public String toString() {
        return String.format("%s len=%d flags=0x%02x stream=%d",
                typeName(), payload.length, flags, streamId);
    }
}
