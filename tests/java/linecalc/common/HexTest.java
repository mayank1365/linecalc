package linecalc.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The hexdump is a deliverable in its own right: docs/annotated-frame.md is made of it. */
class HexTest {

    @Test
    void formatsOffsetHexAndAscii() {
        String dump = Hex.dump("GET".getBytes(StandardCharsets.US_ASCII));
        assertEquals("0000  47 45 54                                          |GET|\n", dump);
    }

    @Test
    void splitsAtSixteenOctetsPerRow() {
        byte[] data = new byte[20];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + i % 26);
        }
        String[] lines = Hex.dump(data).stripTrailing().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("0000  "));
        assertTrue(lines[1].startsWith("0010  "), "second row is offset 0x10");
    }

    @Test
    void replacesNonPrintableOctetsWithADot() {
        String dump = Hex.dump(new byte[] { 0x00, 0x1F, 'A', 0x7F, (byte) 0x80 });
        assertTrue(dump.contains("|..A..|"), dump);
    }

    @Test
    void padsTheFinalShortRowSoTheAsciiColumnStaysAligned() {
        String full = Hex.dump(new byte[16]).split("\n")[0];
        String partial = Hex.dump(new byte[3]).split("\n")[0];
        assertEquals(full.indexOf('|'), partial.indexOf('|'),
                "the ascii column must line up between a full row and a short one");
    }

    @Test
    void putsAnExtraSpaceAfterTheEighthOctet() {
        String dump = Hex.dump(new byte[16]);
        assertTrue(dump.contains("00 00 00 00 00 00 00 00  00"),
                "a gap at the halfway mark makes octets countable by eye");
    }

    @Test
    void honoursOffsetLengthAndIndent() {
        byte[] data = "..hello..".getBytes(StandardCharsets.US_ASCII);
        String dump = Hex.dump(data, 2, 5, "    ");
        assertEquals("    0000  68 65 6c 6c 6f                                    |hello|\n", dump);
    }

    @Test
    void emptyInputProducesNoRows() {
        assertEquals("", Hex.dump(new byte[0]));
    }
}
