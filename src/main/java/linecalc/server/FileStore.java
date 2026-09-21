package linecalc.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import linecalc.protocol.ProtocolException;
import linecalc.protocol.Status;

/**
 * Maps a request path to a file under a document root, and refuses to be talked out of the
 * root.
 *
 * <p>Path traversal is the oldest bug in static file serving, and the reason it keeps
 * happening is that people filter for {@code ".."} in the raw path. That check is defeated by
 * percent-encoding, by {@code ....//}, and by symlinks. The only check that holds is
 * structural: normalise fully, resolve symlinks, and then ask whether the result is still
 * inside the root.
 */
final class FileStore {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"));

    private final Path root;

    FileStore(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("document root is not a directory: " + root);
        }
        // toRealPath resolves symlinks now, so the containment check later compares two paths
        // that are both fully resolved.
        this.root = root.toRealPath();
    }

    Path root() {
        return root;
    }

    /**
     * Resolves a request path to a readable regular file inside the root.
     *
     * @throws ProtocolException 400 if the path is not a path, 403 if it escapes the root,
     *                           404 if there is nothing there
     */
    Path resolve(String requestPath) throws ProtocolException {
        if (requestPath == null || !requestPath.startsWith("/")) {
            throw new ProtocolException(Status.BAD_REQUEST, "path must start with '/'");
        }
        if (requestPath.indexOf('\0') >= 0) {
            throw new ProtocolException(Status.BAD_REQUEST, "path contains NUL");
        }

        String relative = requestPath.substring(1);
        if (relative.isEmpty() || relative.endsWith("/")) {
            relative += "index.html";
        }

        Path candidate;
        try {
            // normalize() collapses ".." textually; the containment check below is what
            // actually enforces the boundary.
            candidate = root.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            throw new ProtocolException(Status.BAD_REQUEST, "unusable path");
        }

        if (!candidate.startsWith(root)) {
            throw new ProtocolException(Status.FORBIDDEN, "path escapes the document root");
        }
        for (Path part : root.relativize(candidate)) {
            if (part.toString().startsWith(".")) {
                // Dotfiles are not secrets, but serving them by default has leaked enough
                // .git directories and .env files to be worth refusing.
                throw new ProtocolException(Status.NOT_FOUND, "not found");
            }
        }
        if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
            throw new ProtocolException(Status.NOT_FOUND, "not found");
        }

        // Re-check after resolving symlinks: a symlink inside the root may point outside it.
        try {
            if (!candidate.toRealPath().startsWith(root)) {
                throw new ProtocolException(Status.FORBIDDEN, "path escapes the document root");
            }
        } catch (IOException e) {
            throw new ProtocolException(Status.NOT_FOUND, "not found");
        }
        return candidate;
    }

    byte[] read(Path file) throws ProtocolException {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ProtocolException(Status.INTERNAL_ERROR, "could not read the file");
        }
    }

    static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
