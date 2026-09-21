package linecalc.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import linecalc.common.Log;

/**
 * The persistent HTTP/1.1 calculator.
 *
 * <pre>
 *   GET /add?a=2&amp;b=3    -&gt; 200  5
 *   GET /sub?a=10&amp;b=4   -&gt; 200  6
 *   GET /mul?a=6&amp;b=7    -&gt; 200  42
 *   GET /div?a=9&amp;b=3    -&gt; 200  3
 *   GET /div?a=1&amp;b=0    -&gt; 400
 *   GET /add?a=x&amp;b=3    -&gt; 400
 *   GET /pow?a=2&amp;b=8    -&gt; 404
 *   POST /add           -&gt; 405
 *   GET /add (no Host)  -&gt; 400
 * </pre>
 *
 * <p>All of that is the easy half. The point of the exercise is that one client socket serves
 * all of those requests: one TCP handshake, many responses, and the connection is still open
 * afterwards.
 */
public final class HttpCalcServer implements Closeable {

    public static final int DEFAULT_PORT = 8080;

    private final ServerSocket serverSocket;
    private final ExecutorService connections;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread acceptLoop;

    public HttpCalcServer(int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port), 128);
        this.connections = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-conn");
            t.setDaemon(true);
            return t;
        });
    }

    /** The bound port, which matters when the caller asked for port 0. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Starts accepting in the background and returns immediately. */
    public HttpCalcServer start() {
        acceptLoop = new Thread(this::acceptForever, "http-accept");
        acceptLoop.setDaemon(true);
        acceptLoop.start();
        return this;
    }

    private void acceptForever() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                connections.execute(new HttpCalcConnection(socket));
            } catch (SocketException e) {
                if (running.get()) {
                    Log.warn("accept failed: %s", e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (running.get()) {
                    Log.warn("accept failed: %s", e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        serverSocket.close();
        connections.shutdownNow();
        try {
            connections.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acceptLoop != null) {
            acceptLoop.interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 1) {
            System.err.println("usage: httpcalc [port]");
            System.exit(2);
        }
        if (args.length == 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("port must be a number, got: " + args[0]);
                System.exit(2);
            }
        }
        HttpCalcServer server = new HttpCalcServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
        }));
        Log.info("calculator listening on http://localhost:%d (keep-alive, idle timeout %ds)",
                server.port(), HttpCalcConnection.IDLE_TIMEOUT_MS / 1000);
        server.start();
        Thread.currentThread().join();
    }
}
