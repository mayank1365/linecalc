package linecalc.protocol;

import java.util.List;
import java.util.Locale;

/**
 * One header field.
 *
 * <p>Names are lowercase everywhere. HTTP/1.1 spent decades on case-insensitive comparison of
 * header names because the wire format permitted both; defining the field as lowercase-only
 * removes the question instead of answering it repeatedly.
 */
public record HeaderField(String name, String value) {

    public HeaderField {
        name = name.toLowerCase(Locale.ROOT);
    }

    /** First value for {@code name} in a decoded block, or null. */
    public static String find(List<HeaderField> fields, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (HeaderField f : fields) {
            if (f.name().equals(wanted)) {
                return f.value();
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name + ": " + value;
    }
}
