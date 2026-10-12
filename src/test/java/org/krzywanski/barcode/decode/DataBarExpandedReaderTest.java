package org.krzywanski.barcode.decode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DataBarExpandedReaderTest {
    private static final char GS = 29;

    /**
     * The one repeated row of zxing-cpp {@code rssexpanded-1/62.png}, divided down to one module.
     * The sample text is {@code 101}.
     */
    private static final int[] LINEAR = {
            0, 1, 2, 1, 1, 2, 5, 1, 4, 1, 1, 8, 4, 1, 1, 1, 3, 1, 7, 1, 1, 2, 1, 2, 1, 5,
            1, 1, 1, 2, 4, 1, 1, 4, 8, 1, 1, 1, 1, 5, 4, 1, 1, 3, 1, 1, 0
    };

    /** Barcode row of zxing-cpp {@code rssexpanded-1/23.png}. The sample text contains one FNC1. */
    private static final int[] WITH_FNC1 = {
            1, 1, 1, 2, 4, 1, 4, 1, 1, 3, 1, 8, 4, 1, 1, 1, 1, 1, 3, 1, 5, 2, 3, 2, 1, 2, 3, 1,
            3, 3, 2, 1, 1, 6, 4, 3, 4, 1, 3, 2, 1, 4, 1, 1, 3, 2, 1, 2, 4, 2, 2, 1, 3, 6, 4,
            1, 1, 1, 1, 3, 5, 1, 3, 2, 1, 2, 1, 1, 1, 2, 2, 3, 5, 1, 1, 8, 2, 3, 1, 1, 0
    };

    /**
     * The three rows of zxing-cpp {@code rssexpandedstacked-1/24.png} that carry finder pairs.
     * The last one is short and ends in a quiet zone. One row, or the first two, is not a symbol.
     */
    private static final int[] STACKED_TOP = {
            0, 1, 1, 1, 2, 1, 5, 4, 2, 1, 1, 8, 4, 1, 1, 1, 3, 1, 7, 1, 1, 2, 1, 2, 1, 2, 3,
            1, 3, 3, 2, 1, 1, 4, 6, 3, 1, 1, 2, 2, 5, 4, 1, 1, 1, 1, 0
    };
    private static final int[] STACKED_MIDDLE = {
            3, 2, 1, 5, 4, 2, 1, 1, 8, 1, 1, 1, 2, 1, 1, 3, 1, 7, 1, 1, 2, 1, 2, 1, 2, 3,
            1, 3, 3, 2, 1, 1, 4, 1, 1, 1, 1, 1, 4, 1, 1, 2, 2, 5, 8
    };
    private static final int[] STACKED_BOTTOM = {
            1, 1, 2, 3, 1, 2, 5, 1, 2, 1, 3, 6, 4, 1, 1, 2, 4, 1, 1, 1, 1, 3, 4, 1, 49
    };

    @Test
    void readsALinearSampleRow() {
        assertEquals("101", DataBarExpandedReader.decode(new boolean[][]{pixels(LINEAR, 1)}));
        assertEquals("101", DataBarExpandedReader.decode(new boolean[][]{pixels(LINEAR, 2)}));
        assertEquals("101", DataBarExpandedReader.decode(new boolean[][]{pixels(LINEAR, 3)}));
        assertEquals("101", DataBarExpandedReader.decode(new boolean[][]{reverse(pixels(LINEAR, 2))}));
    }

    @Test
    void keepsTheFnc1FromALinearSample() {
        assertEquals("1056789" + GS + "11010101",
                DataBarExpandedReader.decode(new boolean[][]{pixels(WITH_FNC1, 1)}));
        assertEquals("1056789" + GS + "11010101",
                DataBarExpandedReader.decode(new boolean[][]{pixels(WITH_FNC1, 2)}));
    }

    @Test
    void assemblesAStackedSymbolAcrossRows() {
        boolean[] top = pixels(STACKED_TOP, 1);
        boolean[] middle = pixels(STACKED_MIDDLE, 1);
        boolean[] bottom = pixels(STACKED_BOTTOM, 1);
        assertNull(DataBarExpandedReader.decode(new boolean[][]{top}));
        assertNull(DataBarExpandedReader.decode(new boolean[][]{middle}));
        assertNull(DataBarExpandedReader.decode(new boolean[][]{bottom}));
        assertNull(DataBarExpandedReader.decode(new boolean[][]{top, middle}));
        assertEquals("105678" + GS + "11010101", DataBarExpandedReader.decode(new boolean[][]{top, middle, bottom}));
        assertEquals("105678" + GS + "11010101", DataBarExpandedReader.decode(new boolean[][]{
                pixels(STACKED_TOP, 2), pixels(STACKED_MIDDLE, 2), pixels(STACKED_BOTTOM, 2)}));
    }

    @Test
    void rejectsADamagedRowAndANull() {
        boolean[] row = pixels(LINEAR, 2);
        int middle = row.length / 2;
        for (int i = middle; i < middle + 8 && i < row.length; i++) {
            row[i] = !row[i];
        }
        assertNull(DataBarExpandedReader.decode(new boolean[][]{row}));
        assertNull(DataBarExpandedReader.decode(null));
        assertNull(DataBarExpandedReader.decode(new boolean[][]{}));
        assertNull(DataBarExpandedReader.decode(new boolean[][]{new boolean[40], null}));
    }

    /**
     * Stacked DataBar Expanded picture from zxing-cpp {@code ThresholdBinarizerTest}.
     * The HRI form is parentheses around AI 91. This text is the element string.
     */
    @Test
    void readsTheOfficialStackedPicture() {
        String bits = """
01010101111011111110111111110000101100100000010100010
00001010000100000001000000001010010011011111101010000
00000101010101010101010101010101010101010101010100000
00001000001110100010100001010101001110000101100110000
10100111110001011101011110000000010001111010011000101
00001000001110100010100001010101001110000101100110000
00000101010101010101010101010101010101010101010100000
00000001110100100001010000001010010101100111000110000
01011110001011011110001111110000101010011000111000010
00000001110100100001010000001010010101100111000110000
00000101010101010101010101010101010101010101010100000
00000110001111101110100001010100001110001110111010000
10101001110000010001011110000001110001110001000100101
00000110001111101110100001010100001110001110111010000
00000101010101010101010101010101010101010101010100000
00001000011011000101010000101010010011100010001100000
01000111100100111010001111000000101100011101110010010
00001000011011000101010000101010010011100010001100000
00000101010101010101010101010101010101010101010100000
00001000110001110110100000000100001011110100001000000
10100111001110001001011111111001110100001011110111101
00001000110001110110100000000100001011110100001000000
00000101010101010101010101010101010101010101010100000
00000010011101111101010010101010010100110000100000000
01011101100010000010001100000000101011001111011110010
00000010011101111101010010101010010100110000100000000
00000101010101010101010101010101010101010101010100000
00000100010111101110100000101010001100000110011010000
10111011101000010001011111000000110011111001100101101
00000100010111101110100000101010001100000110011010000
00000101010101010101010101010101010101010101010100000
00000011000110001001000000010101010000011110011010000
01011100111001110110011111100000101111100001100101010
00000011000110001001000000010101010000011110011010000
00000101010101010101010101010101010101010101010100000
00001011100010001110100000000010001011111010001100000
10100100011101110001011111111100110100000101110011101
00001011100010001110100000000010001011111010001100000
00000101010101010101010101010101010101010101010100000
00001000111010000101000101010101010100001011000110000
01000111000101111010011000000000101011110100111000010
                """.replace("\n", "").replace(" ", "");
        assertEquals(41 * 53, bits.length());
        boolean[][] rows = new boolean[41][53];
        for (int i = 0; i < bits.length(); i++) {
            rows[i / 53][i % 53] = bits.charAt(i) == '1';
        }
        assertEquals("9112345678901234567890123456789012345678901234567890123456789012345678",
                DataBarExpandedReader.decode(rows));
    }

    /** First run is white, matching {@code OneDRow.runs}. A leading zero starts the row on black. */
    private static boolean[] pixels(int[] runs, int scale) {
        int width = 0;
        for (int run : runs) {
            width += run * scale;
        }
        boolean[] row = new boolean[width];
        int at = 0;
        boolean black = false;
        for (int run : runs) {
            int drawn = run * scale;
            for (int i = 0; i < drawn; i++) {
                row[at++] = black;
            }
            black = !black;
        }
        return row;
    }

    private static boolean[] reverse(boolean[] row) {
        boolean[] out = new boolean[row.length];
        for (int i = 0; i < row.length; i++) {
            out[i] = row[row.length - 1 - i];
        }
        return out;
    }
}
