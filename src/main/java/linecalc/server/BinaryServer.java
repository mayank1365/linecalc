package linecalc.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import linecalc.common.Log;

/**
 * {@code bserve} — the LCB/1 static file server.
 *
 * <pre>
 *   $ ./bserve ./www 9000
 *
 *   accept a TCP connection
 *   read one binary request frame
 *   map the path to a file under a root
 *   reply: status, headers, the bytes
 *   404 if it is not there
 *   400 if the frame is malformed
 *   and keep the connection open
 * </pre>
 */
public final class BinaryServer implements Closeable {

    public static final int DEFAULT_PORT = 9000;

    private final ServerSocket serverSocket;
    private final FileStore files;
    private final ExecutorService connections;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread acceptLoop;

    public BinaryServer(Path root, int port) throws IOException {
        this.files = new FileStore(root);
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port), 128);
        this.connections = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "lcb-conn");
            t.setDaemon(true);
            return t;
        });
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public Path root() {
        return files.root();
    }

    public BinaryServer start() {
        acceptLoop = new Thread(this::acceptForever, "lcb-accept");
        acceptLoop.setDaemon(true);
        acceptLoop.start();
        return this;
    }

    private void acceptForever() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                connections.execute(new BinaryConnection(socket, files));
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
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: bserve <document-root> [port]      (default port "
                    + DEFAULT_PORT + ")");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        int port = DEFAULT_PORT;
        if (args.length == 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("port must be a number, got: " + args[1]);
                System.exit(2);
            }
        }

        BinaryServer server;
        try {
            server = new BinaryServer(root, port);
        } catch (IOException e) {
            System.err.println("bserve: " + e.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
        }));
        Log.info("bserve serving %s on port %d (LCB/1, persistent)", server.root(), server.port());
        server.start();
        Thread.currentThread().join();
    }
}
