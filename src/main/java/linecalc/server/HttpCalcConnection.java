package linecalc.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

import linecalc.calculator.CalcException;
import linecalc.calculator.Calculator;
import linecalc.calculator.Operation;
import linecalc.common.Log;
import linecalc.protocol.HttpException;
import linecalc.protocol.HttpRequest;
import linecalc.protocol.HttpRequestParser;
import linecalc.protocol.HttpResponse;
import linecalc.protocol.Status;

/**
 * One client socket, for as long as it lives.
 *
 * <p>The loop below is the entire assignment: parse a request, answer it, and go back around
 * on the same streams. The streams are created once and never replaced, so the buffered bytes
 * of a pipelined request survive into the next iteration.
 */
final class HttpCalcConnection implements Runnable {

    /**
     * How long a connection may sit idle between requests before we close it.
     *
     * <p>Something has to bound this or an idle client holds a thread and a file descriptor
     * forever, which is a denial of service you inflicted on yourself. Thirty seconds is in the
     * same range as nginx's default keepalive_timeout of 75s and Apache's 5s, and we close
     * silently rather than sending 408: a client that has not started a request has nothing to
     * read our status line with, and one that is mid-request will simply see the close and
     * retry, which is what RFC 9112 section 9.6 tells it to do.
     */
    static final int IDLE_TIMEOUT_MS = 30_000;

    private final Socket socket;

    HttpCalcConnection(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        String peer = socket.getRemoteSocketAddress().toString();
        int served = 0;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            // Created once per connection. Recreating these per request is the classic way to
            // lose pipelined bytes that are already sitting in the buffer.
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8192);

            while (true) {
                HttpRequest request;
                try {
                    request = HttpRequestParser.parse(in);
                } catch (SocketTimeoutException e) {
                    Log.info("%s idle for %ds, closing after %d request(s)",
                            peer, IDLE_TIMEOUT_MS / 1000, served);
                    return;
                } catch (HttpException e) {
                    // We could not frame this request. Answer it, then stop: we no longer know
                    // where the next request starts, and guessing would corrupt it.
                    HttpResponse res = errorResponse(e.status(), e.getMessage());
                    res.header("Connection", e.framingIntact() ? "keep-alive" : "close");
                    res.writeTo(out);
                    out.flush();
                    served++;
                    Log.info("%s -> %d %s", peer, e.status(), e.getMessage());
                    if (!e.framingIntact()) {
                        return;
                    }
                    continue;
                }

                if (request == null) {
                    Log.info("%s closed after %d request(s)", peer, served);
                    return;
                }

                HttpResponse response = handle(request);
                boolean close = request.wantsClose();
                response.header("Connection", close ? "close" : "keep-alive");
                if (!close) {
                    response.header("Keep-Alive", "timeout=" + IDLE_TIMEOUT_MS / 1000);
                }
                response.writeTo(out);
                out.flush();
                served++;
                Log.info("%s %s -> %d", peer, request, response.status());

                if (close) {
                    return;
                }
            }
        } catch (IOException e) {
            Log.warn("%s dropped after %d request(s): %s", peer, served, e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }

    /**
     * Routes one framed request.
     *
     * <p>Path first, then method. That ordering is what makes {@code POST /add} a 405 (the
     * resource exists, the verb is wrong) while {@code GET /pow} is a 404 (there is no such
     * resource to have an opinion about the verb).
     */
    private HttpResponse handle(HttpRequest request) {
        Operation op = Operation.fromPath(request.path());
        if (op == null) {
            return errorResponse(Status.NOT_FOUND, "no such operation: " + request.path());
        }

        boolean head = request.method().equals("HEAD");
        if (!head && !request.method().equals("GET")) {
            return errorResponse(Status.METHOD_NOT_ALLOWED,
                    request.method() + " is not allowed on " + request.path())
                    .header("Allow", "GET, HEAD");
        }

        long result;
        try {
            result = Calculator.evaluate(op, request.param("a"), request.param("b"));
        } catch (CalcException e) {
            return errorResponse(Status.BAD_REQUEST, e.getMessage());
        }

        // The body is the bare number with no trailing newline, so a client can compare it
        // byte for byte against the expected answer.
        HttpResponse response = HttpResponse.text(Status.OK, Long.toString(result));
        return head ? response.omitBody() : response;
    }

    private static HttpResponse errorResponse(int status, String message) {
        return HttpResponse.text(status, status + " " + Status.reason(status) + ": " + message + "\n");
    }
}
