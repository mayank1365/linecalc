package linecalc.common;

/**
 * Canonical hexdump formatting, used by {@code bcurl -v} and by the annotated dump in
 * {@code docs/annotated-frame.md}.
 */
public final class Hex {

    private Hex() {
    }

    public static String dump(byte[] data) {
        return dump(data, 0, data.length, "");
    }

    /**
     * Formats {@code len} bytes as {@code offset  hex bytes  |printable|}, 16 bytes per row.
     */
    public static String dump(byte[] data, int off, int len, String indent) {
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < len; row += 16) {
            int n = Math.min(16, len - row);
            out.append(indent).append(String.format("%04x  ", row));
            for (int i = 0; i < 16; i++) {
                out.append(i < n ? String.format("%02x ", data[off + row + i] & 0xFF) : "   ");
                if (i == 7) {
                    out.append(' ');
                }
            }
            out.append(" |");
            for (int i = 0; i < n; i++) {
                int c = data[off + row + i] & 0xFF;
                out.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
            }
            out.append("|\n");
        }
        return out.toString();
    }
}
