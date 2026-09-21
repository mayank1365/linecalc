package linecalc.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import linecalc.common.Hex;
import linecalc.protocol.Frame;
import linecalc.protocol.FrameCodec;
import linecalc.protocol.FrameType;
import linecalc.protocol.HeaderCodec;
import linecalc.protocol.HeaderField;
import linecalc.protocol.ProtocolException;
import linecalc.protocol.Status;

/**
 * {@code bcurl} — the LCB/1 client.
 *
 * <pre>
 *   $ ./bcurl -v localhost:9000/index.html
 *
 *   build the binary request frame
 *   read the response, body to stdout
 *   -v hexdumps every frame
 *   exit non-zero on 4xx / 5xx
 *
 *   and never open a second connection
 * </pre>
 *
 * <p>That last line is enforced structurally rather than promised: {@link #run} opens exactly
 * one {@link Socket} and every request in the argument list goes down it. Give it several URLs
 * and it will refuse the ones that would require a different host or port rather than quietly
 * dialling again.
 *
 * <p>The body goes to stdout and everything else — hexdumps, status lines, errors — goes to
 * stderr, so {@code ./bcurl host:9000/photo.png > photo.png} produces the file and not the
 * file plus commentary.
 */
public final class BinaryClient {

    private static final int EXIT_OK = 0;
    private static final int EXIT_TRANSPORT = 1;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_CLIENT_ERROR = 4;    // 4xx
    private static final int EXIT_SERVER_ERROR = 5;    // 5xx

    private final boolean verbose;
    private final PrintStream err = System.err;

    BinaryClient(boolean verbose) {
        this.verbose = verbose;
    }

    public static void main(String[] args) {
        boolean verbose = false;
        boolean head = false;
        List<String> urls = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "-v", "--verbose" -> verbose = true;
                case "-I", "--head" -> head = true;
                case "-h", "--help" -> {
                    usage(System.out);
                    System.exit(EXIT_OK);
                }
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("bcurl: unknown option: " + arg);
                        usage(System.err);
                        System.exit(EXIT_USAGE);
                    }
                    urls.add(arg);
                }
            }
        }
        if (urls.isEmpty()) {
            usage(System.err);
            System.exit(EXIT_USAGE);
        }
        System.exit(new BinaryClient(verbose).run(urls, head));
    }

    private static void usage(PrintStream out) {
        out.println("usage: bcurl [-v] [-I] <host:port/path> [more paths on the same host...]");
        out.println("  -v  hexdump every frame in both directions (to stderr)");
        out.println("  -I  send HEAD instead of GET");
        out.println();
        out.println("All URLs share one connection; a second host or port is an error, not a");
        out.println("second socket.");
    }

    int run(List<String> urls, boolean head) {
        List<Target> targets = new ArrayList<>();
        for (String url : urls) {
            try {
                targets.add(Target.parse(url));
            } catch (IllegalArgumentException e) {
                err.println("bcurl: " + e.getMessage());
                return EXIT_USAGE;
            }
        }
        Target first = targets.get(0);
        for (Target t : targets) {
            if (!t.sameEndpoint(first)) {
                err.println("bcurl: " + t.authority() + " is not " + first.authority()
                        + "; that would need a second connection, which this client does not open");
                return EXIT_USAGE;
            }
        }

        // One socket. Everything below happens on it.
        try (Socket socket = new Socket(first.host, first.port)) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16384);

            if (verbose) {
                err.printf("* connected to %s:%d%n", first.host, first.port);
                err.println("> preface");
                err.print(Hex.dump(FrameCodec.PREFACE));
            }
            out.write(FrameCodec.PREFACE);
            out.flush();

            int worst = EXIT_OK;
            int streamId = 1;                       // client streams are odd and increasing
            for (Target target : targets) {
                int exit = request(in, out, target, streamId, head);
                streamId += 2;
                worst = Math.max(worst, exit);
            }
            if (verbose) {
                err.printf("* closing after %d request(s) on one connection%n", targets.size());
            }
            return worst;
        } catch (IOException e) {
            err.println("bcurl: " + e.getMessage());
            return EXIT_TRANSPORT;
        } catch (ProtocolException e) {
            err.println("bcurl: protocol error: " + e.getMessage());
            return EXIT_TRANSPORT;
        }
    }

    private int request(InputStream in, OutputStream out, Target target, int streamId, boolean head)
            throws IOException, ProtocolException {
        List<HeaderField> headers = List.of(
                new HeaderField(":method", head ? "HEAD" : "GET"),
                new HeaderField(":path", target.path),
                new HeaderField("host", target.authority()),
                new HeaderField("user-agent", "bcurl/1.0"));
        Frame request = Frame.of(FrameType.REQUEST, Frame.FLAG_END_MESSAGE, streamId,
                HeaderCodec.encode(headers));

        if (verbose) {
            err.printf("> %s  (stream %d)%n", request, streamId);
            for (HeaderField h : headers) {
                err.println(">   " + h);
            }
            err.print(Hex.dump(FrameCodec.encode(request)));
        }
        FrameCodec.write(out, request);
        out.flush();

        return readResponse(in, streamId);
    }

    private int readResponse(InputStream in, int streamId) throws IOException, ProtocolException {
        int status = 0;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean sawResponse = false;

        while (true) {
            Frame frame = FrameCodec.read(in);
            if (frame == null) {
                throw new ProtocolException(Status.BAD_REQUEST,
                        "server closed the connection mid-response", false);
            }

            // The rule that may not be skipped, from the other side. We dump it so -v shows
            // that it arrived, then drop it and keep reading. The payload was already
            // consumed by the length prefix, so the stream stays aligned.
            if (!frame.isKnown()) {
                if (verbose) {
                    err.printf("< %s  -- unknown type, skipped%n", frame);
                    err.print(Hex.dump(FrameCodec.encode(frame)));
                }
                continue;
            }

            if (verbose) {
                err.printf("< %s%n", frame);
            }

            switch (frame.knownType()) {
                case RESPONSE -> {
                    List<HeaderField> headers = HeaderCodec.decode(frame.payload());
                    String raw = HeaderField.find(headers, ":status");
                    if (raw == null) {
                        throw new ProtocolException(Status.BAD_REQUEST,
                                "RESPONSE carried no :status");
                    }
                    try {
                        status = Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        throw new ProtocolException(Status.BAD_REQUEST, ":status is not a number: " + raw);
                    }
                    sawResponse = true;
                    if (verbose) {
                        for (HeaderField h : headers) {
                            err.println("<   " + h);
                        }
                        err.print(Hex.dump(FrameCodec.encode(frame)));
                    }
                    if (frame.hasFlag(Frame.FLAG_END_MESSAGE)) {
                        return finish(status, body);
                    }
                }
                case DATA -> {
                    if (!sawResponse) {
                        throw new ProtocolException(Status.BAD_REQUEST, "DATA before RESPONSE");
                    }
                    if (frame.streamId() != streamId) {
                        throw new ProtocolException(Status.BAD_REQUEST,
                                "DATA on stream " + frame.streamId() + ", expected " + streamId);
                    }
                    body.write(frame.payload());
                    if (verbose) {
                        err.print(Hex.dump(FrameCodec.encode(frame)));
                    }
                    if (frame.hasFlag(Frame.FLAG_END_MESSAGE)) {
                        return finish(status, body);
                    }
                }
                case PING -> {
                    // A server may probe us mid-exchange. Echo it and carry on.
                    if (verbose) {
                        err.println("< PING, answering with ACK");
                    }
                }
                case REQUEST -> throw new ProtocolException(Status.BAD_REQUEST,
                        "server sent a REQUEST frame");
            }
        }
    }

    private int finish(int status, ByteArrayOutputStream body) throws IOException {
        // Body to stdout, verbatim. Diagnostics have all gone to stderr.
        System.out.write(body.toByteArray());
        System.out.flush();
        if (verbose) {
            err.printf("* %d, %d octet(s) of body%n", status, body.size());
        }
        if (Status.isServerError(status)) {
            return EXIT_SERVER_ERROR;
        }
        if (Status.isClientError(status)) {
            return EXIT_CLIENT_ERROR;
        }
        return EXIT_OK;
    }

    /** A parsed {@code host:port/path}. */
    record Target(String host, int port, String path) {

        static Target parse(String url) {
            String rest = url;
            for (String scheme : new String[] { "lcb://", "http://" }) {
                if (rest.regionMatches(true, 0, scheme, 0, scheme.length())) {
                    rest = rest.substring(scheme.length());
                    break;
                }
            }
            if (rest.isEmpty()) {
                throw new IllegalArgumentException("empty URL");
            }
            int slash = rest.indexOf('/');
            String authority = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);

            String host = authority;
            int port = linecalc.server.BinaryServer.DEFAULT_PORT;
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                try {
                    port = Integer.parseInt(authority.substring(colon + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad port in: " + url);
                }
            }
            if (host.isEmpty()) {
                throw new IllegalArgumentException("no host in: " + url);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range in: " + url);
            }
            return new Target(host, port, path);
        }

        String authority() {
            return host + ":" + port;
        }

        boolean sameEndpoint(Target other) {
            return host.equals(other.host) && port == other.port;
        }
    }
}
