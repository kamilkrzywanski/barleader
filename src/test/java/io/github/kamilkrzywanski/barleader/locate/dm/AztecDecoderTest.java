package io.github.kamilkrzywanski.barleader.locate.dm;

import com.google.zxing.FormatException;
import com.google.zxing.ResultPoint;
import com.google.zxing.aztec.AztecDetectorResult;
import com.google.zxing.aztec.decoder.Decoder;
import com.google.zxing.aztec.encoder.AztecCode;
import com.google.zxing.aztec.encoder.Encoder;
import com.google.zxing.common.BitMatrix;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barleader.locate.dm.DmGeometry.Bits;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AztecDecoderTest {
    @Test
    void roundTripsEveryCompactAndFullLayerCount() throws Exception {
        for (int layers = 1; layers <= 4; layers++) {
            assertAgrees(Encoder.encode("A", 33, -layers));
        }
        for (int layers = 1; layers <= 32; layers++) {
            assertAgrees(Encoder.encode("A", 33, layers));
        }
    }

    @Test
    void roundTripsCompactFullLowerAndPunctuation() throws Exception {
        assertAgrees("A");
        assertAgrees("AZTEC-FLAT");
        assertAgrees("aztec-lower-42");
        assertAgrees("Hello, Aztec!");
        assertAgrees("café");
        assertAgrees("X".repeat(80));
        assertAgrees(Encoder.encode("FULL-AZTEC", 33, 4));
        assertAgrees(Encoder.encode("C2", 33, -2));
    }

    @Test
    void roundTripsUtf8EciAndABinaryShift() throws Exception {
        AztecCode utf8 = Encoder.encode("Zażółć", 33, 0, StandardCharsets.UTF_8);
        assertEquals("Zażółć", javaText(utf8));
        assertEquals("Zażółć", ours(utf8));
        AztecCode binary = Encoder.encode(new byte[]{0, 1, 2, (byte) 0xFF, 'A'});
        assertEquals(javaText(binary), ours(binary));
    }

    /**
     * Payloads from zxing-cpp {@code AZEncodeDecodeTest}. The Java writer chooses
     * the symbol. Both decoders have to return the same text. Layer counts are not asserted.
     */
    @Test
    void roundTripsOfficialPayloads() throws Exception {
        assertAgrees("Abc123!");
        assertAgrees("Lorem ipsum. http://test/");
        assertAgrees("AAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAANAAAAN");
        assertAgrees("http://test/~!@#*^%&)__ ;:'\"[]{}\\|-+-=`1029384");
        assertAgrees("http://test/~!@#*^%&)__ ;:'\"[]{}\\|-+-=`1029384756<>/?abcFour score and seven our forefathers brought forth");
        assertAgrees("""
            In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id elementum sapien dolor et diam.
                """.strip());
        assertAgrees("""
            In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id elementum sapien dolor et diam. Donec ac nunc sodales elit placerat eleifend. Sed ornare luctus ornare. Vestibulum vehicula, massa at pharetra fringilla, risus justo faucibus erat, nec porttitor nibh tellus sed est. Ut justo diam, lobortis eu tristique ac, p.In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id elementum sapien dolor et diam. Donec ac nunc sodales elit placerat eleifend. Sed ornare luctus ornare. Vestibulum vehicula, massa at pharetra fringilla, risus justo faucibus erat, nec porttitor nibh tellus sed est. Ut justo diam, lobortis eu tristique ac, p. In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id elementum sapien dolor et diam.
                """.strip());
        assertAgrees("""
            In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id elementum sapien dolor et diam. Donec ac nunc sodales elit placerat eleifend. Sed ornare luctus ornare. Vestibulum vehicula, massa at pharetra fringilla, risus justo faucibus erat, nec porttitor nibh tellus sed est. Ut justo diam, lobortis eu tristique ac, p.In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id elementum sapien dolor et diam. Donec ac nunc sodales elit placerat eleifend. Sed ornare luctus ornare. Vestibulum vehicula, massa at pharetra fringilla, risus justo faucibus erat, nec porttitor nibh tellus sed est. Ut justo diam, lobortis eu tristique ac, p. In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id elementum sapien dolor et diam. Donec ac nunc sodales elit placerat eleifend. Sed ornare luctus ornare. Vestibulum vehicula, massa at pharetra fringilla, risus justo faucibus erat, nec porttitor nibh tellus sed est. Ut justo diam, lobortis eu tristique ac, p.In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem. Nulla ultrices, elit pellentesque aliquet laoreet, justo erat pulvinar nisi, id elementum sapien dolor et diam. Donec ac nunc sodales elit placerat eleifend. Sed ornare luctus ornare. Vestibulum vehicula, massa at pharetra fringilla, risus justo faucibus erat, nec porttitor nibh tellus sed est. Ut justo diam, lobortis eu tris. In ut magna vel mauris malesuada dictum. Nulla ullamcorper metus quis diam cursus facilisis. Sed mollis quam id justo rutrum sagittis. Donec laoreet rutrum est, nec convallis mauris condimentum sit amet. Phasellus gravida, justo et congue auctor, nisi ipsum viverra erat, eget hendrerit felis turpis nec lorem.
                """.strip());
    }

    /**
     * Locked to zxing-cpp {@code AZEncodeDecodeTest::AztecWriter}. The symbol is the
     * ZXing encoder at each published error-correction percent, with the default layer
     * count. The text is the UTF-8 payload. Layer counts are not asserted.
     */
    @Test
    void readsTheOfficialUtf8WriterPayload() throws Exception {
        String text = "\u20ac 1 sample data.";
        for (int percent : new int[] {25, 100, 300, 500}) {
            assertEquals(text, ours(Encoder.encode(text, percent, 0, StandardCharsets.UTF_8)), "ecc " + percent);
        }
    }

    /**
     * Locked to zxing-cpp {@code AZEncodeDecodeTest::AztecWriter}. The symbol is the
     * ZXing encoder at its default error correction. The text is the writer payload.
     * Layer counts are not asserted.
     */
    @Test
    void readsTheOfficialDefaultWriterPayload() throws Exception {
        String text = "In ut magna vel mauris malesuada";
        assertEquals(text, ours(Encoder.encode(text)));
    }

    /** Four border modules flipped. Both decoders must still return the payload. */
    @Test
    void agreesWithZxingAfterFourBorderFlips() throws Exception {
        for (int layers : new int[]{1, -1, 4, 5, 12}) {
            AztecCode code = Encoder.encode("A", 33, layers);
            BitMatrix matrix = code.getMatrix();
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            matrix.flip(width / 2, 0);
            matrix.flip(width / 2, height - 1);
            matrix.flip(0, height / 2);
            matrix.flip(width - 1, height / 2);
            assertEquals("A", ours(code), "layers " + layers);
            assertEquals("A", javaOrNull(code), "layers " + layers);
        }
    }

    @Test
    void rejectsADestroyedMatrix() throws Exception {
        AztecCode code = Encoder.encode("AZTEC-FLAT");
        BitMatrix matrix = code.getMatrix();
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if ((x + y) % 2 == 0) {
                    matrix.flip(x, y);
                }
            }
        }
        assertNull(AztecDecoder.decode(bits(matrix), code.isCompact(), code.getLayers(), code.getCodeWords()));
        assertNull(AztecDecoder.decode(null, true, 1, 1));
        assertNull(AztecDecoder.decode(new Bits(11, 11), true, 0, 1));
    }

    @Test
    void followsCppOnFnc1AndStructuredAppend() {
        assertEquals("A", corrected("00010"));
        assertEquals("2001", corrected("0000000000000111100100001000100011"));
        assertEquals("AB", corrected("00010000000000000000011"));
        assertEquals("aB", corrected("111000001000000000000001110000011"));
        assertEquals("99B", corrected("11110101110111110000000000000000011"));
        assertEquals("A", corrected("000100000000000000"));
        assertEquals("12", corrected("1111000110100000000000000"));
        assertEquals("A", corrected("1110111101000100010100010"));
        assertEquals("2001", corrected("111011110100010001010000000000000111100100001000100011"));
        assertEquals("AB", corrected("1110111101000100010100010000000000000000011"));
        assertEquals("AB\u001dC", corrected("0001000011000000000000000100"));
        assertEquals("ABC\u001dD", corrected("000100001100100000000000000000101"));
        assertEquals("1\u001dA", corrected("1111000111110000000000000000010"));
        assertEquals("A\\Bé", corrected("000000000000101010001011101101011110100011111110000111101001"));
        assertEquals("A", corrected("0000000000001010100010"));
        assertEquals("2001", corrected("000000000000000000000000010101111100100001000100011"));
        assertEquals("AB", corrected("0000000000001010100010000000000000000011"));
        assertEquals("A", corrected("111011110100010001010000000000001010100010"));
        assertEquals("2001", corrected(
                "11101111010001000101000000000000000000000000010101111100100001000100011"));
        assertEquals("AB", corrected("111011110100010001010000000000001010100010000000000000000011"));
        assertEquals("\u001dU\u0010\u0094\u00e2\u00e7\u0014\u00ff", corrected(
                "1111101000000111010101010100010000100101001110001011100111000101001111111111111"));
        assertEquals("U\u0010\u0094\u00e2\u00e7\u0014\u00ff", corrected(
                "00000000000001011011111001100001000010010100111000101110011100010100111111111111"));
        assertEquals("A", words(2));
        assertEquals("A", words(29, 29, 2, 5, 2));
        assertEquals("A", words(29, 29, 3, 5, 2));
        assertEquals("A", words(29, 29, 4, 5, 2));
        assertEquals("A", words(29, 29, 5, 5, 2));
        assertEquals("A", words(29, 29, 2, 2, 2));
        assertEquals("A", words(29, 29, 6, 5, 2));
        assertEquals("A", words(29, 29, 1, 1, 2, 5, 2));
        assertEquals("A", words(29, 29, 1, 10, 5, 1, 2, 5, 2));
        assertEquals(" DA", words(29, 29, 1, 5, 2));
        assertEquals("da", words(29, 29, 28, 5, 2));
        assertEquals("A A", words(29, 29, 2, 1, 2));
        assertEquals("Aa", words(29, 29, 2, 28, 2));
        assertEquals("AD", words(29, 29, 2, 5));
        assertEquals(" IDADA", words(29, 29, 1, 10, 5, 2, 5, 2));
        assertEquals(" I D ADA", words(29, 29, 1, 10, 1, 5, 1, 2, 5, 2));
        assertEquals(" ADA", words(29, 29, 1, 10, 1, 2, 5, 1, 2, 5, 2));
    }

    /**
     * Locked to zxing-cpp {@code AZHighLevelEncoderTest}. The strings are the encoder's
     * data bits, read back by {@code Decode(BitArray)}. A dot is 0 and X is 1.
     * Rows that only publish a bit count need the encoder and are not included.
     */
    @Test
    void readsTheOfficialHighLevelBits() {
        assertEquals("A. b.", corrected("0001000000000111110000011111101101"));
        assertEquals("Lorem ipsum.", corrected(
                "011011110010000100110011001110000010101010001101001011001110111101101"));
        assertEquals("Lo. Test 123.", corrected(
                "011011110010000000000001111100101010011010100101011111000010011010001011101"));
        assertEquals("Lo...x", corrected("0110111100100001111011011101110111101110011001"));
        assertEquals(". x://abc/.", corrected(
                "000000001111100110010000010101000001010000000101000001000011001000000010100111101101"));
        assertEquals("ABCdEFG", corrected("000100001100100111110000101100100001100011101000"));
        assertEquals("~", corrected("1110111010"));
        assertEquals("}", corrected("0000011110"));
        assertEquals("N\u0000N", corrected("0111111111000010000000001111"));
        assertEquals("N\u0000n", corrected("0111111111000100000000001101110"));
        assertEquals("N\u0000\u0080 A", corrected("01111111110001000000000100000000000100010"));
        assertEquals("\u0000a\u00ff\u0080 A", corrected(
                "1111100100000000000110000111111111100000000000100010"));
        assertEquals("1234\u0000", corrected("1111000110100010101101110111110000100000000"));
        assertEquals("ABC. DEF\r\n", corrected("00010000110010000000000110010100110001110000000010"));
        assertEquals("A. : , \r\n", corrected("00010111011111000011001010010000010"));
        assertEquals("A. 1234", corrected("0001011110110100010011010001010110"));
        assertEquals("A\u0080. \u0080", corrected("00010111110010010000000001011100010000010000000"));
    }

    /** Locked to zxing-cpp {@code AZDecoderTest} matrices. The {@code ]z} identifier is not part of the text. */
    @Test
    void readsTheOfficialMatrices() {
        assertEquals("88888TTTTTTTTTTTTTTTTTTTTTTTTTTTTTT",
                AztecDecoder.decode(art("""
        XXXXX..XXX...XXX..XXX..
        XXX..XXX..XXXX..XXX..XX
        .X.XX...X.X.XXXX..X..XX
        .X.XX..XX..X..X.X...X.X
        .XX.XX....X.......XX..X
        .XX.XXXXXXXXXXXXXXX..X.
        .XXXXX...........X.XXX.
        .X.X.X.XXXXXXXXX.XXX.XX
        .X.XXX.X.......X.XX...X
        .XX.XX.X.XXXXX.X.XXXX.X
        .XX.XX.X.X...X.X.X.XXX.
        .X.X.X.X.X.X.X.X.X.X.X.
        .XXX.X.X.X...X.X.XX.XX.
        .XXXXX.X.XXXXX.X.XXX.XX
        XX.XXX.X.......X.X.XX.X
        .X...X.XXXXXXXXX.X.X..X
        .XX.XX...........XX.XX.
        .XXX.XXXXXXXXXXXXXX.XX.
        X..X..X..XX.XX.......XX
        X.XXXXX.XXXXX..X.X.X..X
        XXX.XXXX.....XXX...X..X
        XX..XXX..XXXX..XXX..XX.
        ..XXX..XXX...XXX..XXXX.
        """), false, 2, 30));
        assertNull(AztecDecoder.decode(art("""
        XX.X...XX...X..XXX.X.XXXXX.
        XX..XX.....XX...XX...X.X..X
        X...XX..XXX.XX.XXXX.XX..X..
        ....X.XX..XX.XX.X.XXXX.X..X
        XXX..XXXXX.....XX...X.X.X.X
        XX........X...X.XXX.X..X...
        XX..X.....XX.....X....X..XX
        ...X.X.X.....XXXXXX......XX
        X...X.XXXXXX..XXX.X.XXXXXX.
        X..XXX.XXXXXXXXXXXXX...X.XX
        ....XX...X.......XX...XX.X.
        ...XXX..XX.XXXXX.X..X......
        X....X.X.X.X...X.X.XX.XX.XX
        X.X..X.X.X.X.X.X.X.....X.XX
        X.XXX..X.X.X...X.X.XXX...XX
        XXXXXXXX.X.XXXXX.X.X.X.XXX.
        .......X.X.......XXXX...XXX
        XX..X..X.XXXXXXXXXXXXX..X.X
        XXX.XXXX..XXXX..X....X..XXX
        ....X.XXX....XXXX..XXXX....
        ..X..X.X...X.XX.XX.X...X.X.
        XX..X..XXXXXXX..X.XXXXXXX..
        X.XX..XX.....X......XX.XXX.
        X..XX..XX.X.X....X.X..X..X.
        X.X.X..X.XXXXXXXX.XXXX..XX.
        XXXX...X..XXX.XX..X....XXX.
        XX.X.X...X.X....XX.X..XX...
        """), true, 4, 16));
        assertNull(AztecDecoder.decode(art("""
        .XX..X.XX...X..XXX...XX.XX.
        XX.XX..X...XX...XX.XXX.X.XX
        ....X...XXX.XX.XXXX.XX..X..
        X.XX..X...XX.XX.X.XX.....X.
        XX.X..X.XX.....XX.....X...X
        X..X......X...X.XXXXXXX...X
        X..XX..X..XX.....X.....XXX.
        ..XXXX.X.....XXXXXX......XX
        X...X.XXXXXX..XXX.X.XXXXXX.
        X..XXX.XXXXXXXXXXXXX...X.XX
        ....XX...X.......XX...XX.X.
        ...XXX..XX.XXXXX.X..X......
        X....X.X.X.X...X.X.XX.XX.XX
        X.X..X.X.X.X.X.X.X.....X.XX
        X.XXX..X.X.X...X.X.XXX...XX
        XXXXXXXX.X.XXXXX.X.X.X.XXX.
        .......X.X.......XXXX...XXX
        XX..X..X.XXXXXXXXXXXXX..X.X
        XXX.XXXX..XXXX..X....X..XXX
        ..XXXXX.X....XXXX..XXX.X.X.
        ..XX.X.X...X.XX.XX....XX...
        X...X.X.XXXXXX..X.XXXXX.X..
        .X...XXX.....X.....XXXXX.X.
        X..X.XXXX.X.X....X.XX.X..X.
        X...XX.X.XXXXXXXX.XXXX..XX.
        .XXXX..X..XXX.XX..X....XXX.
        XX...XX..X.X....XX.X..X.X.X
        """), true, 4, 16));
    }

    /** One character per module. X is black. Dots keep a white edge that a text block would trim. */
    private static Bits art(String rows) {
        String[] lines = rows.split("\n", -1);
        int height = lines.length;
        while (height > 0 && lines[height - 1].isEmpty()) {
            height--;
        }
        int width = lines[0].length();
        Bits bits = new Bits(width, height);
        for (int y = 0; y < height; y++) {
            if (lines[y].length() != width) {
                throw new IllegalArgumentException("row " + y);
            }
            for (int x = 0; x < width; x++) {
                if (lines[y].charAt(x) == 'X') {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }

    private static String corrected(String bits) {
        boolean[] row = new boolean[bits.length()];
        for (int i = 0; i < bits.length(); i++) {
            row[i] = bits.charAt(i) == '1';
        }
        return AztecDecoder.decodeCorrected(row);
    }

    private static String words(int... codes) {
        boolean[] bits = new boolean[codes.length * 5];
        int at = 0;
        for (int code : codes) {
            for (int bit = 4; bit >= 0; bit--) {
                bits[at++] = ((code >> bit) & 1) != 0;
            }
        }
        return AztecDecoder.decodeCorrected(bits);
    }

    private static void assertAgrees(String text) throws Exception {
        assertAgrees(Encoder.encode(text));
    }

    private static void assertAgrees(AztecCode code) throws Exception {
        assertEquals(javaText(code), ours(code));
    }

    private static String ours(AztecCode code) {
        return AztecDecoder.decode(bits(code.getMatrix()), code.isCompact(), code.getLayers(), code.getCodeWords());
    }

    private static String javaOrNull(AztecCode code) {
        try {
            return javaText(code);
        } catch (FormatException failed) {
            return null;
        }
    }

    private static String javaText(AztecCode code) throws FormatException {
        ResultPoint[] points = {
                new ResultPoint(0, 0), new ResultPoint(0, 0), new ResultPoint(0, 0), new ResultPoint(0, 0)
        };
        AztecDetectorResult detected = new AztecDetectorResult(
                code.getMatrix(),
                points,
                code.isCompact(),
                code.getCodeWords(),
                code.getLayers()
        );
        return new Decoder().decode(detected).getText();
    }

    private static Bits bits(BitMatrix matrix) {
        Bits bits = new Bits(matrix.getWidth(), matrix.getHeight());
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }
}
