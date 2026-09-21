package linecalc.protocol;

/**
 * A request we are refusing, carrying the status the peer should see.
 *
 * <p>{@link #framingIntact()} is the interesting field. If we failed while parsing headers we
 * do not know where this request ends, so the only honest thing to do after answering is to
 * close the connection — guessing would make byte n+1 somebody else's problem. If we failed
 * after consuming the whole request (a missing Host, say), the stream is still aligned and the
 * connection can serve the next request.
 */
public class HttpException extends Exception {

    private final int status;
    private final boolean framingIntact;

    public HttpException(int status, String message) {
        this(status, message, false);
    }

    public HttpException(int status, String message, boolean framingIntact) {
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
