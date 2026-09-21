package linecalc.protocol;

import java.util.List;
import java.util.Locale;

/**
 * The ten header names we actually send, numbered.
 *
 * <p>This is HPACK's first mechanism and nothing more. Real traffic reuses a tiny vocabulary
 * of header names over and over, so writing {@code content-length} as fifteen octets on every
 * single response is pure waste. Numbering the ten names we use turns each of those into one
 * octet. Anything outside the table still travels as a literal, so the table being short costs
 * correctness nothing — only bytes.
 *
 * <p>The list is frozen for version 1. Indices are on the wire, so reordering or inserting a
 * name would silently change the meaning of bytes already in flight; a version 2 may only
 * append.
 */
public final class StaticTable {

    /** Index 1 through 10. Index 0 is reserved to mean "a literal name follows". */
    public static final List<String> NAMES = List.of(
            ":method",          // 1
            ":path",            // 2
            ":status",          // 3
            "host",             // 4
            "content-length",   // 5
            "content-type",     // 6
            "user-agent",       // 7
            "server",           // 8
            "date",             // 9
            "connection");      // 10

    /** Reserved: signals that an explicit name follows instead of an index. */
    public static final int LITERAL = 0;

    private StaticTable() {
    }

    /** @return the 1-based index of {@code name}, or {@link #LITERAL} if it is not in the table */
    public static int indexOf(String name) {
        int i = NAMES.indexOf(name.toLowerCase(Locale.ROOT));
        return i < 0 ? LITERAL : i + 1;
    }

    /** @return the name at a 1-based index, or null if the index is not defined in version 1 */
    public static String name(int index) {
        return index >= 1 && index <= NAMES.size() ? NAMES.get(index - 1) : null;
    }
}
