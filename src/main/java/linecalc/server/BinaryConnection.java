package linecalc.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import linecalc.common.Bytes;
import linecalc.common.Log;
import linecalc.protocol.Frame;
import linecalc.protocol.FrameCodec;
import linecalc.protocol.FrameType;
import linecalc.protocol.HeaderCodec;
import linecalc.protocol.HeaderField;
import linecalc.protocol.ProtocolException;
import linecalc.protocol.Status;

/** One LCB/1 client connection, from preface to close. */
final class BinaryConnection implements Runnable {

    static final int IDLE_TIMEOUT_MS = 60_000;
    /** Body octets per DATA frame. Well under the 64 KiB frame cap. */
    static final int DATA_CHUNK = 16 * 1024;

    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneOffset.UTC);

    private final Socket socket;
    private final FileStore files;
    private int highestStreamId = 0;

    BinaryConnection(Socket socket, FileStore files) {
        this.socket = socket;
        this.files = files;
    }

    @Override
    public void run() {
        String peer = socket.getRemoteSocketAddress().toString();
        int served = 0;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16384);

            if (!readPreface(in)) {
                Log.warn("%s sent a bad preface, closing", peer);
                return;
            }

            while (true) {
                Frame frame;
                try {
                    frame = FrameCodec.read(in);
                } catch (SocketTimeoutException e) {
                    Log.info("%s idle for %ds, closing after %d request(s)",
                            peer, IDLE_TIMEOUT_MS / 1000, served);
                    return;
                } catch (ProtocolException e) {
                    respondError(out, 0, e.status(), e.getMessage());
                    Log.warn("%s -> %d %s", peer, e.status(), e.getMessage());
                    if (!e.framingIntact()) {
                        return;     // We no longer know where the next frame starts.
                    }
                    continue;
                }

                if (frame == null) {
                    Log.info("%s closed after %d request(s)", peer, served);
                    return;
                }

                // The rule that may not be skipped: a type we do not know is stepped over,
                // not rejected. FrameCodec has already consumed exactly Length octets, so the
                // stream is sitting on the next header.
                if (!frame.isKnown()) {
                    Log.info("%s skipped %s (%d octets) -- unknown type, connection continues",
                            peer, frame.typeName(), frame.length());
                    continue;
                }

                FrameType type = frame.knownType();
                if (type == FrameType.PING) {
                    handlePing(out, frame);
                    continue;
                }
                if (type != FrameType.REQUEST) {
                    // RESPONSE and DATA are server-to-client only; version 1 has no request
                    // bodies.
                    respondError(out, frame.streamId(), Status.BAD_REQUEST,
                            type + " is not valid from a client");
                    continue;
                }

                served++;
                handleRequest(out, frame, peer);
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

    private boolean readPreface(InputStream in) throws IOException {
        try {
            byte[] preface = Bytes.readExactly(in, FrameCodec.PREFACE.length);
            return Arrays.equals(preface, FrameCodec.PREFACE);
        } catch (EOFException e) {
            return false;
        }
    }

    private void handlePing(OutputStream out, Frame ping) throws IOException {
        if (ping.hasFlag(Frame.FLAG_ACK)) {
            return;     // An echo. Answering it would echo forever.
        }
        if (ping.streamId() != 0 || ping.length() > 8) {
            respondError(out, 0, Status.BAD_REQUEST, "malformed PING");
            return;
        }
        FrameCodec.write(out, Frame.of(FrameType.PING, Frame.FLAG_ACK, 0, ping.payload()));
        out.flush();
    }

    private void handleRequest(OutputStream out, Frame frame, String peer) throws IOException {
        int streamId = frame.streamId();
        try {
            if (streamId == 0 || (streamId & 1) == 0) {
                throw new ProtocolException(Status.BAD_REQUEST,
                        "client streams must be odd and non-zero, got " + streamId);
            }
            if (streamId <= highestStreamId) {
                throw new ProtocolException(Status.BAD_REQUEST,
                        "stream " + streamId + " reuses or reorders an id");
            }
            highestStreamId = streamId;

            if (!frame.hasFlag(Frame.FLAG_END_MESSAGE)) {
                throw new ProtocolException(Status.BAD_REQUEST,
                        "REQUEST must set END_MESSAGE; version 1 has no request bodies");
            }

            List<HeaderField> headers = HeaderCodec.decode(frame.payload());
            String method = HeaderField.find(headers, ":method");
            String path = HeaderField.find(headers, ":path");
            String host = HeaderField.find(headers, "host");
            if (method == null || path == null) {
                throw new ProtocolException(Status.BAD_REQUEST, "missing :method or :path");
            }
            if (host == null) {
                throw new ProtocolException(Status.BAD_REQUEST, "missing host");
            }

            boolean head = method.equals("HEAD");
            if (!head && !method.equals("GET")) {
                throw new ProtocolException(Status.METHOD_NOT_ALLOWED,
                        method + " is not supported; use GET or HEAD");
            }

            Path file = files.resolve(path);
            byte[] body = files.read(file);
            Log.info("%s stream %d %s %s -> 200 (%d octets)", peer, streamId, method, path,
                    body.length);
            respond(out, streamId, Status.OK, FileStore.contentType(file), body, head);
        } catch (ProtocolException e) {
            Log.warn("%s stream %d -> %d %s", peer, streamId, e.status(), e.getMessage());
            respondError(out, streamId, e.status(), e.getMessage());
        }
    }

    private void respondError(OutputStream out, int streamId, int status, String message)
            throws IOException {
        byte[] body = FileStore.utf8(status + " " + Status.reason(status) + ": " + message + "\n");
        respond(out, streamId, status, "text/plain; charset=utf-8", body, false);
    }

    /**
     * Writes one RESPONSE frame and then the body as DATA frames.
     *
     * <p>{@code content-length} is sent even though {@code END_MESSAGE} is what actually ends
     * the body, so a client can size its buffer once instead of growing it.
     */
    private void respond(OutputStream out, int streamId, int status, String contentType,
                         byte[] body, boolean omitBody) throws IOException {
        List<HeaderField> headers = new ArrayList<>();
        headers.add(new HeaderField(":status", Integer.toString(status)));
        headers.add(new HeaderField("server", "linecalc-bserve/1.0"));
        headers.add(new HeaderField("date", IMF_FIXDATE.format(ZonedDateTime.now(ZoneOffset.UTC))));
        headers.add(new HeaderField("content-type", contentType));
        headers.add(new HeaderField("content-length", Integer.toString(body.length)));

        // A HEAD response ends at the RESPONSE frame, so that frame carries END_MESSAGE.
        int flags = omitBody ? Frame.FLAG_END_MESSAGE : 0;
        FrameCodec.write(out, Frame.of(FrameType.RESPONSE, flags, streamId,
                HeaderCodec.encode(headers)));

        if (!omitBody) {
            int sent = 0;
            do {
                int chunk = Math.min(DATA_CHUNK, body.length - sent);
                boolean last = sent + chunk == body.length;
                FrameCodec.write(out, Frame.of(FrameType.DATA, last ? Frame.FLAG_END_MESSAGE : 0,
                        streamId, Arrays.copyOfRange(body, sent, sent + chunk)));
                sent += chunk;
            } while (sent < body.length);
            // An empty body still needs one zero-length DATA to carry END_MESSAGE, which the
            // do/while above produces.
        }
        out.flush();
    }
}
