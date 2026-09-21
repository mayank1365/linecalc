package linecalc.protocol;

/**
 * The four frame types version 1 defines.
 *
 * <p>Deliberately small. The type field is 8 bits wide, which leaves 252 unused codes — and
 * the protocol's central rule is that a receiver meeting one of those MUST skip it cleanly
 * rather than fail. That rule is what lets a version 2 add frame types without every version 1
 * peer on the network breaking.
 */
public enum FrameType {

    /** Client to server: a request's header block. Carries no body in version 1. */
    REQUEST(0x01),
    /** Server to client: a response's status and header block. */
    RESPONSE(0x02),
    /** Server to client: body bytes. The last one carries END_MESSAGE. */
    DATA(0x03),
    /** Either direction: liveness probe. The peer echoes it back with ACK set. */
    PING(0x04);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** @return the type for {@code code}, or null if this version does not know it */
    public static FrameType of(int code) {
        for (FrameType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }
}
