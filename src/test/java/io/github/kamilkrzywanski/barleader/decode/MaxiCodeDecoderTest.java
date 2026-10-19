package io.github.kamilkrzywanski.barleader.decode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locked to zxing-cpp {@code MCDecoderTest.cpp}. C++ literals with a leading zero are octal, so
 * those bytes are written here in decimal. The module grids are the pure symbols sampled by
 * {@code ExtractPureBits}: one module per bit, {@code 1} is black.
 */
class MaxiCodeDecoderTest {
    private static final int[] MODE2_ISO = {34, 20, 45, 20, 17, 18, 2, 18, 7, 0};
    private static final int[] MODE3_ISO = {3, 8, 28, 13, 28, 44, 0, 14, 28, 62};

    @Test
    void decodesMode2AndMode3Headers() {
        assertEquals("152382802\u001d840\u001d001\u001d1", text(new int[] {49}, 2, null));
        // Alphanumeric postcode is six characters. The ISO example pads B1050 with a trailing space.
        assertEquals("B1050 \u001d056\u001d999\u001d1", text(new int[] {49}, 3, null));

        assertEquals("1234\u001d999\u001d999\u001d1", text(new int[] {49}, 2, new int[] {34, 52, 4, 0, 0, 0, 49, 57, 31, 62}));
        assertEquals("0123\u001d999\u001d999\u001d1", text(new int[] {49}, 2, new int[] {50, 30, 0, 0, 0, 0, 49, 57, 31, 62}));
        assertEquals("1234\u001d999\u001d999\u001d1", text(new int[] {49}, 2, new int[] {18, 5, 13, 47, 53, 1, 49, 57, 31, 62}));
        assertEquals("000000001\u001d999\u001d999\u001d1", text(new int[] {49}, 2, new int[] {18, 0, 0, 0, 0, 32, 50, 57, 31, 62}));
        assertEquals("107374182\u001d999\u001d999\u001d1", text(new int[] {49}, 2, new int[] {50, 63, 63, 63, 63, 47, 50, 57, 31, 62}));
        assertEquals("12345\u001d999\u001d999\u001d1", text(new int[] {49}, 2, new int[] {18, 14, 48, 0, 0, 16, 49, 63, 31, 62}));
        assertEquals("00123456\u001d999\u001d999\u001d1", text(new int[] {49}, 2, new int[] {2, 16, 34, 7, 0, 0, 50, 57, 35, 62}));

        // "[)>" + RS + "01" + GS + "96" stays in front of the postcode. The insert is at byte 9.
        int[] carrier = {59, 42, 41, 59, 40, 30, 48, 49, 29, 57, 54, 24};
        assertEquals("[)>\u001e01\u001d961234\u001d999\u001d999\u001dX",
                text(carrier, 2, new int[] {34, 52, 4, 0, 0, 0, 49, 57, 31, 62}));
    }

    @Test
    void readsStructuredAppendWithoutWritingItIntoTheText() {
        for (int mode : new int[] {2, 3, 4, 5, 6}) {
            assertAppend(new int[] {49}, mode, -1, -1);
            assertAppend(new int[] {33, 22, 49}, mode, 2, 7);
            assertAppend(new int[] {33, 7, 49}, mode, 0, 8);
            assertAppend(new int[] {33, 55, 49}, mode, 6, 8);
            assertAppend(new int[] {33, 63, 49}, mode, 7, 8);
            assertAppend(new int[] {33, 1, 49}, mode, 0, 2);
            assertAppend(new int[] {33, 9, 49}, mode, 1, 2);
            assertAppend(new int[] {33, 0, 49}, mode, 0, 0);
            assertAppend(new int[] {33, 26, 49}, mode, 3, 0);
        }
        assertEquals("1", text(new int[] {33, 22, 49}, 4, null));
        assertEquals("1", text(new int[] {49}, 4, null));
        assertEquals("1", text(new int[] {49}, 5, null));
        assertEquals("1", text(new int[] {49}, 6, null));
    }

    @Test
    void decodesShiftsNumericShiftAndEci() {
        assertEquals("a1", text(new int[] {59, 1, 49}, 4, null));
        assertEquals("000000000", text(new int[] {31, 0, 0, 0, 0, 0}, 4, null));
        assertEquals("000000123", text(new int[] {31, 0, 0, 0, 1, 59}, 4, null));
        // Set C keeps NS at index 31. A one-symbol shift reaches it.
        assertEquals("000000000", text(new int[] {60, 31, 0, 0, 0, 0, 0}, 4, null));

        assertEquals("\u0104", text(new int[] {27, 4, 61, 37}, 4, null));
        assertEquals("A\u0104", text(new int[] {1, 27, 4, 61, 37}, 4, null));
        assertEquals("\u00a1", text(new int[] {61, 37}, 4, null));
        assertEquals("A", text(new int[] {27, 26, 1}, 4, null));
        assertEquals("A", text(new int[] {27, 0x20, 26, 1}, 4, null));
        assertEquals("A", text(new int[] {27, 0x30, 0, 26, 1}, 4, null));
        assertEquals("A", text(new int[] {27, 0x38, 0, 0, 26, 1}, 4, null));
    }

    @Test
    void readsSampledSymbols() {
        assertSymbol(MODE4, "[)>\u001e01\u001d96123450000\u001d222\u001d111\u001dMODE4", -1, -1);
        assertSymbol(MIXED_SETS, "ABCDE12abcde1\u00c0\u00c1\u00c2\u00c3\u00a2\u00a3\u00a4\u00a51\u00e0\u00e1\u00e2\u00e3abcde123A123456789\u00e0\u00e1\u00e2\u00e3\u00a2\u00a3\u00a4\u00a5abcd\u00da\u00db\u00dc\u00dd123456789\u00be", -1, -1);
        assertSymbol(SEQUENCE, "123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901", 1, 3);
        assertSymbol(WIKIPEDIA, "Wikipedia, the free encyclopedia", -1, -1);

        MaxiCodeDecoder.Message ecis = MaxiCodeDecoder.readModules(grid(MIXED_ECIS));
        assertEquals("\u00e1\u00a1\u0126\u0401\u2018\u0104\u30c6\u0093_\u00a1\ud800\udf36\u9f98\u9fa4\u00e9\uac00\u00b0\u00a2\u9f44\u060c", ecis.text);
        assertEquals(-1, ecis.index);
        assertEquals(-1, ecis.count);
        assertArrayEquals(new byte[] {(byte) 0xe1, (byte) 0xa1, (byte) 0xa1, (byte) 0xa1, (byte) 0xa1, (byte) 0xa1, (byte) 0x83, (byte) 0x65, (byte) 0x93, (byte) 0x5f, (byte) 0x00, (byte) 0xa1, (byte) 0xf0, (byte) 0x90, (byte) 0x8c, (byte) 0xb6, (byte) 0xf9, (byte) 0xd5, (byte) 0xf9, (byte) 0xa1, (byte) 0xe9, (byte) 0xb0, (byte) 0xa1, (byte) 0xb0, (byte) 0xa2, (byte) 0xf7, (byte) 0xfe, (byte) 0xa1}, ecis.bytes);
    }

    @Test
    void resamplesAPureImage() {
        boolean[][][] symbols = {grid(MODE4), grid(MIXED_SETS), grid(MIXED_ECIS), grid(SEQUENCE), grid(WIKIPEDIA)};
        for (boolean[][] symbol : symbols) {
            String expected = MaxiCodeDecoder.decodeModules(symbol);
            for (int scale = 2; scale <= 4; scale++) {
                boolean[][] image = render(symbol, scale);
                assertEquals(expected, MaxiCodeDecoder.decode(image));
                assertTrue(same(symbol, MaxiCodeDecoder.sampled(image)));
            }
        }
    }

    @Test
    void correctsOneModuleAndRejectsARuinedGrid() {
        boolean[][] symbol = grid(MODE4);
        String expected = MaxiCodeDecoder.decodeModules(symbol);
        symbol[0][0] = !symbol[0][0];
        assertEquals(expected, MaxiCodeDecoder.decodeModules(symbol));
        for (int y = 0; y < 12; y++) {
            Arrays.fill(symbol[y], false);
        }
        assertNull(MaxiCodeDecoder.decodeModules(symbol));
        assertNull(MaxiCodeDecoder.decodeModules(null));
        assertNull(MaxiCodeDecoder.decodeModules(new boolean[33][30]));
        assertNull(MaxiCodeDecoder.read(null));
    }

    @Test
    void rejectsABadStreamAndABlankImage() {
        assertNull(MaxiCodeDecoder.message(new int[] {4, 49}, 4));
        assertNull(MaxiCodeDecoder.message(new int[] {49}, 1));
        assertNull(MaxiCodeDecoder.message(null, 4));
        assertNull(MaxiCodeDecoder.decode(null));
        assertNull(MaxiCodeDecoder.decode(new boolean[0][]));
        assertNull(MaxiCodeDecoder.decode(new boolean[][] {new boolean[4], new boolean[3]}));
        assertNull(MaxiCodeDecoder.decode(new boolean[20][20]));
        assertNull(MaxiCodeDecoder.decode(new boolean[40][40]));
        boolean[][] ink = new boolean[40][40];
        for (boolean[] row : ink) {
            Arrays.fill(row, true);
        }
        assertNull(MaxiCodeDecoder.decode(ink));
    }

    private static final String[] MODE4 = {
            "110101110110111110111100000011",
            "010101010111000011011011111010",
            "110110110001001010100110110001",
            "010101010101010101010111110000",
            "000000000000000000000011101010",
            "101010101010101010101011001000",
            "010101010101010101010101010101",
            "000000000000000000000000000000",
            "101010101010101010101010101011",
            "010101011111110100010101010100",
            "000000000011000000011100000011",
            "101010100000011001111010101010",
            "010101010010100010100001010100",
            "000000111010111101110000000000",
            "101010000110101010100010101000",
            "010101110101100110000001010100",
            "000000101001100001101000000001",
            "101010111101100110001110101000",
            "010101000010101010100101010101",
            "000000000010111101011000000010",
            "101010110100100010110010101011",
            "010101011100011000101001010100",
            "000000000010011001001000000010",
            "101010101110101101111110101010",
            "010101010101010101011010100111",
            "000000000000000000001000001110",
            "101010101010101010101100110110",
            "001001101110101110111001111110",
            "110011111101100001111011100011",
            "011010101001010101111000100000",
            "101000100011101111011100010100",
            "100010001101000011011000111000",
            "001110110011111010110011000010"
    };

    private static final String[] MIXED_SETS = {
            "000000001111111100000000111111",
            "000010100100111100000000111100",
            "011100101110000000100111010111",
            "001100000000111111110101010100",
            "001100000000111100010101101001",
            "101111011000101010011000110110",
            "000000001111111100100010111110",
            "000010101100000000111010111000",
            "011100101110011110111101000010",
            "000000001011001100010111111010",
            "000000000001000010000111111011",
            "110110001110011000011110101000",
            "111101000010110110001101010100",
            "111110110011011011100110010100",
            "010101101011110110110111001010",
            "110000110011011011001100001100",
            "111000101010010101101000001101",
            "000011110011011011001101101100",
            "111001101011110110100001011010",
            "110100000111011011010000001110",
            "000100100000110110000110011101",
            "110110110100000000000111100010",
            "011110101010001011100011101000",
            "011110001011000001011000011100",
            "010101010101010101010110011111",
            "000000000000000000001001000110",
            "101010101010101010101001011000",
            "000100110111001111010001001110",
            "011010101001001000101100011101",
            "111011110101111001011101101000",
            "011101111000010000101111010101",
            "011100101001111011010000011010",
            "001110011011001000111100101110"
    };

    private static final String[] MIXED_ECIS = {
            "110110001101100011011010110111",
            "111001101110010111100100111000",
            "101011111010111010101101101011",
            "011111110010111000111111101000",
            "110101110000000110010011100100",
            "011100100000001110111100001110",
            "101011001101101011101111111110",
            "010111001110010111001110110000",
            "111001001010110110001010101011",
            "111011101111001100000010011110",
            "110111111001001110011001111111",
            "001010000010011000100011110100",
            "101110000000110110111111011000",
            "101101011011011011101011100110",
            "101010001011110110111010101110",
            "111010010011011011001100110010",
            "111101101010010101101001110011",
            "100111010011011011001110101110",
            "011101110011110110110010111100",
            "011110011111011011110001011010",
            "101010101010110110001011110010",
            "100111011000000000110111110010",
            "011011011110110011100111110110",
            "110101100011110101001010110000",
            "101110110110101101011011101001",
            "111110110001011110001010010110",
            "101011101111001010100101101110",
            "110111010000100110001011011000",
            "111110111001001011110000110111",
            "000101001110011101110010101000",
            "000011010111100101110100110001",
            "000010111010010001011010001100",
            "111000110101010010101010001101"
    };

    private static final String[] SEQUENCE = {
            "000100000010110111110010110011",
            "001110101111101101111011100010",
            "001101100111101011011111101111",
            "101110011011111100011000100100",
            "100011011101111000111100000001",
            "111110001110101010111100111110",
            "111001111000001010000111101010",
            "001100101000011101110000101110",
            "011110011011111111010000001100",
            "001011100111001100010001011010",
            "111110001101011110011001101001",
            "101110001000011110000101010010",
            "111101001010111110110010100001",
            "110000101111011011110100111010",
            "011111111101110110111001111101",
            "001010010011011011101011111010",
            "001110101010110101101010111000",
            "001110101011011011101100000100",
            "010000110101110110000000111100",
            "111010100101011011111011010010",
            "110110110100111110011001100011",
            "101100101100011100000111111110",
            "111101000010010111101000010000",
            "100001010011000001001011001000",
            "111011110101101001001001111011",
            "010001100010010100011101100100",
            "000010111100010101100010111000",
            "000010100111110010000100101110",
            "001111010000000111011001100110",
            "111100110100011101101010101010",
            "011011001010010110100101001101",
            "100111110100101100001110000010",
            "011011111100111001001100000000"
    };

    private static final String[] WIKIPEDIA = {
            "001101100000010010000001000011",
            "000011100110111000101011101100",
            "100011000010110101101011100101",
            "010101010000000010000000100000",
            "000000000001101000111100010001",
            "101010101010001000110011101100",
            "010101010101010101010101010101",
            "000000000000000000000000000010",
            "101010101010101010101010101010",
            "010101010011111000000101010110",
            "000000000001110010011100000011",
            "101010100100011000100010101000",
            "010101000010110110101101010111",
            "000000010110011011000100000000",
            "101010111010100010110110101000",
            "010101010001011011000001010110",
            "000000001010010110101000000000",
            "101010100001011011001010101000",
            "010101001110100110110001010100",
            "000000111110011011100100000000",
            "101010010010110110011110101011",
            "010101010110000001110101010100",
            "000000001110101011001000000000",
            "101010101110100001101010101000",
            "010101010101010101010111110010",
            "000000000000000000000110111010",
            "101010101010101010100010110110",
            "000111011111100010100110110110",
            "010010001100110010111001011101",
            "000111010100111100100100010110",
            "110100101100111001110100111010",
            "011111001010111101001111001110",
            "100011001000010011110111111010"
    };

    private static void assertSymbol(String[] rows, String expected, int index, int count) {
        MaxiCodeDecoder.Message message = MaxiCodeDecoder.readModules(grid(rows));
        assertEquals(expected, message.text);
        assertEquals(index, message.index);
        assertEquals(count, message.count);
        assertEquals(expected, new String(message.bytes, StandardCharsets.ISO_8859_1));
    }

    private static void assertAppend(int[] message, int mode, int index, int count) {
        int[] header = mode == 2 ? MODE2_ISO : mode == 3 ? MODE3_ISO : null;
        MaxiCodeDecoder.Message parsed = parsed(message, mode, header);
        assertEquals(index, parsed.index, "mode " + mode);
        assertEquals(count, parsed.count, "mode " + mode);
    }

    private static String text(int[] message, int mode, int[] header) {
        return parsed(message, mode, header).text;
    }

    /** Pads with set-A pad (codeword 33), the same filler {@code MCDecoderTest.cpp} uses. */
    private static MaxiCodeDecoder.Message parsed(int[] message, int mode, int[] header) {
        int length = mode == 5 ? 78 : 94;
        int[] data = new int[length];
        Arrays.fill(data, 33);
        if (mode == 2 || mode == 3) {
            int[] primary = header == null ? (mode == 2 ? MODE2_ISO : MODE3_ISO) : header;
            System.arraycopy(primary, 0, data, 0, primary.length);
            System.arraycopy(message, 0, data, 10, message.length);
        } else {
            data[0] = mode;
            System.arraycopy(message, 0, data, 1, message.length);
        }
        return MaxiCodeDecoder.message(data, mode);
    }

    private static boolean[][] grid(String[] rows) {
        boolean[][] bits = new boolean[rows.length][rows[0].length()];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                bits[y][x] = rows[y].charAt(x) == '1';
            }
        }
        return bits;
    }

    /** Odd rows sit half a module to the right, which is the pure MaxiCode raster. */
    private static boolean[][] render(boolean[][] modules, int scale) {
        int quiet = scale;
        int width = 30 * scale + scale / 2 + 2 * quiet;
        int height = 33 * scale + 2 * quiet;
        boolean[][] image = new boolean[height][width];
        for (int y = 0; y < modules.length; y++) {
            int origin = quiet + ((y & 1) * scale) / 2;
            for (int x = 0; x < modules[y].length; x++) {
                if (!modules[y][x]) {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        image[quiet + y * scale + dy][origin + x * scale + dx] = true;
                    }
                }
            }
        }
        return image;
    }

    private static boolean same(boolean[][] left, boolean[][] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        for (int y = 0; y < left.length; y++) {
            if (!Arrays.equals(left[y], right[y])) {
                return false;
            }
        }
        return true;
    }
}
