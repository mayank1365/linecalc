package linecalc.protocol;

/**
 * A violation of the binary protocol.
 *
 * <p>{@link #framingIntact()} carries the same distinction as in {@link HttpException}, and it
 * is what makes recovery possible at all. A frame whose <em>payload</em> we could not parse is
 * survivable: the 24-bit length prefix already told us how many bytes to consume, so we
 * consumed them, answered 400, and the next frame header is exactly where it should be. A
 * frame whose <em>header</em> we could not read leaves us with no idea where the next one
 * starts, and the only correct move is to close.
 */
public class ProtocolException extends Exception {

    private final int status;
    private final boolean framingIntact;

    public ProtocolException(int status, String message) {
        this(status, message, true);
    }

    public ProtocolException(int status, String message, boolean framingIntact) {
        super(message);
        this.status = status;
        this.framingIntact = framingIntact;
    }

    public int status() {
        return status;
    }

    public boolean framingIntact() {
        return framingIntact;
    }
}
