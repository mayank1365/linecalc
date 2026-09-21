package linecalc.common;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal stderr logger.
 *
 * <p>Everything goes to stderr on purpose: {@code bcurl} writes the response body to stdout,
 * so stdout has to stay free of diagnostics for {@code ./bcurl url > file} to be useful.
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String fmt, Object... args) {
        emit("INFO", fmt, args);
    }

    public static void warn(String fmt, Object... args) {
        emit("WARN", fmt, args);
    }

    private static void emit(String level, String fmt, Object... args) {
        System.err.println(TS.format(ZonedDateTime.now()) + " " + level + " " + String.format(fmt, args));
    }
}
