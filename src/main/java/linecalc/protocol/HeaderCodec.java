package linecalc.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import linecalc.common.Bytes;

/**
 * Encodes and decodes a header block: HPACK's first two mechanisms, and only those.
 *
 * <p>Each field is:
 *
 * <pre>
 *   +---------------+
 *   |  Name code(8) |          1..10  index into the static table
 *   +---------------+          0      a literal name follows
 *   | Name len (8)  |  only when the code is 0
 *   | Name (len)    |
 *   +---------------+
 *   | Value len(16) |          always present
 *   | Value (len)   |
 *   +---------------+
 * </pre>
 *
 * <p>There is no field count. The block runs to the end of the frame payload, whose length the
 * frame header already gave us — repeating that as a count would be a second source of truth
 * about the same thing, and the two could disagree.
 *
 * <p>Values are always length-prefixed byte strings, even {@code :status}, which travels as
 * the ASCII {@code "200"} rather than a 16-bit integer. Two octets are saved by special-casing
 * it and one uniform rule is lost; a reader that must know a field's identity before it can
 * work out that field's width cannot skip a field it does not recognise.
 *
 * <p>What this deliberately does <em>not</em> have is HPACK's third mechanism, the dynamic
 * table: entries added at runtime and referenced by later requests. It is where the
 * compression ratio actually comes from, and also where HPACK gets hard — both peers must
 * evolve identical tables in lockstep, which is what made CRIME-style attacks possible.
 * Version 1 leaves it out.
 */
public final class HeaderCodec {

    /** A literal name may not be longer than the 8-bit length prefix allows. */
    public static final int MAX_NAME_BYTES = 255;
    /** A value may not be longer than the 16-bit length prefix allows. */
    public static final int MAX_VALUE_BYTES = 65535;

    private HeaderCodec() {
    }

    public static byte[] encode(List<HeaderField> fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        for (HeaderField field : fields) {
            byte[] value = field.value().getBytes(StandardCharsets.UTF_8);
            if (value.length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("header value too long: " + field.name());
            }
            int index = StaticTable.indexOf(field.name());
            if (index != StaticTable.LITERAL) {
                out.write(index);
            } else {
                byte[] name = field.name().getBytes(StandardCharsets.UTF_8);
                if (name.length == 0 || name.length > MAX_NAME_BYTES) {
                    throw new IllegalArgumentException("header name too long: " + field.name());
                }
                out.write(StaticTable.LITERAL);
                out.write(name.length);
                out.write(name, 0, name.length);
            }
            byte[] len = new byte[2];
            Bytes.putU16(len, 0, value.length);
            out.write(len, 0, 2);
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    /**
     * Decodes a complete header block.
     *
     * @throws ProtocolException if the block is malformed. The frame's own length already
     *                           bounded the damage, so this is always recoverable: the caller
     *                           answers 400 and reads the next frame.
     */
    public static List<HeaderField> decode(byte[] block) throws ProtocolException {
        List<HeaderField> fields = new ArrayList<>();
        int pos = 0;
        while (pos < block.length) {
            int code = Bytes.u8(block, pos++);
            String name;
            if (code == StaticTable.LITERAL) {
                if (pos >= block.length) {
                    throw new ProtocolException(Status.BAD_REQUEST, "truncated literal header name");
                }
                int nameLen = Bytes.u8(block, pos++);
                if (nameLen == 0) {
                    throw new ProtocolException(Status.BAD_REQUEST, "empty header name");
                }
                if (pos + nameLen > block.length) {
                    throw new ProtocolException(Status.BAD_REQUEST, "header name runs past the block");
                }
                name = new String(block, pos, nameLen, StandardCharsets.UTF_8);
                pos += nameLen;
                if (!isLowercaseToken(name)) {
                    throw new ProtocolException(Status.BAD_REQUEST, "malformed header name: " + name);
                }
            } else {
                name = StaticTable.name(code);
                if (name == null) {
                    // An index this version has never heard of. Unlike an unknown frame type
                    // this is not skippable: without knowing the name we still know the
                    // field's width, but we would be dropping a header whose meaning could be
                    // load-bearing. Version 2 may only append to the table, so seeing this at
                    // all means the peer is out of spec.
                    throw new ProtocolException(Status.BAD_REQUEST,
                            "undefined static table index: " + code);
                }
            }

            if (pos + 2 > block.length) {
                throw new ProtocolException(Status.BAD_REQUEST, "truncated header value length");
            }
            int valueLen = Bytes.u16(block, pos);
            pos += 2;
            if (pos + valueLen > block.length) {
                throw new ProtocolException(Status.BAD_REQUEST, "header value runs past the block");
            }
            String value = new String(block, pos, valueLen, StandardCharsets.UTF_8);
            pos += valueLen;
            fields.add(new HeaderField(name, value));
        }
        return fields;
    }

    private static boolean isLowercaseToken(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
