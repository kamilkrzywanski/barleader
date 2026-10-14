package io.github.kamilkrzywanski.barcode.decode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DataBarDecoderTest {
    /** Guards added by {@code ODDataBarReaderTest.cpp}, around the composite pattern in that file. */
    private static final int[] PATTERN = {
            1, 1,
            2, 3, 1, 2, 1, 2, 4, 1, 3, 3, 7, 1, 1, 3, 1, 2, 1, 1, 1, 4, 2, 4, 1, 1, 2, 3, 1, 1, 2, 1, 1, 2,
            8, 3, 3, 2, 2, 1, 4, 1, 1, 2,
            1, 1
    };
    /** Element string. zxing-cpp prints the same digits as HRI {@code (01)01234567890128}. */
    private static final String TEXT = "0101234567890128";

    @Test
    void readsTheCppCompositeVector() {
        boolean[] row = pixels(PATTERN, 1);
        assertNull(DataBarDecoder.decode(new boolean[][]{row}));
        assertEquals(TEXT, DataBarDecoder.decode(new boolean[][]{row, row}));
        assertEquals(TEXT, DataBarDecoder.decodeOmni(row));
        for (int scale : new int[]{2, 3}) {
            boolean[] scaled = pixels(PATTERN, scale);
            assertEquals(TEXT, DataBarDecoder.decodeOmni(scaled));
            assertEquals(TEXT, DataBarDecoder.decode(new boolean[][]{scaled, scaled}));
        }
    }

    /**
     * Middle row of zxing-cpp {@code test/samples/databarltd-1/00.png}, divided down to one module.
     * The sample text is {@code 0100000000000000}.
     */
    private static final int[] LIMITED = {
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            6, 1, 6, 3, 1, 1, 1, 3, 1, 1, 1, 1,
            2, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            6, 1, 6, 3, 1, 1, 5
    };

    @Test
    void readsLimitedSampleRow() {
        assertEquals("0100000000000000", DataBarDecoder.decodeLimited(pixels(LIMITED, 1)));
        assertEquals("0100000000000000", DataBarDecoder.decodeLimited(pixels(LIMITED, 2)));
        assertEquals("0100000000000000", DataBarDecoder.decodeLimited(pixels(LIMITED, 3)));
    }

    @Test
    void rejectsADamagedModuleAndABlankRow() {
        boolean[] row = pixels(PATTERN, 2);
        int middle = row.length / 2;
        for (int i = middle; i < middle + 8 && i < row.length; i++) {
            row[i] = !row[i];
        }
        assertNull(DataBarDecoder.decodeOmni(row));
        assertNull(DataBarDecoder.decode(new boolean[][]{row, row}));
        assertNull(DataBarDecoder.decode(new boolean[][]{new boolean[40], new boolean[40]}));
        assertNull(DataBarDecoder.decode(new boolean[][]{}));
        assertNull(DataBarDecoder.decode(null));
        assertNull(DataBarDecoder.decodeOmni(new boolean[0]));
        assertNull(DataBarDecoder.decodeOmni(null));
        boolean[] limited = pixels(LIMITED, 2);
        int at = limited.length / 2;
        for (int i = at; i < at + 8 && i < limited.length; i++) {
            limited[i] = !limited[i];
        }
        assertNull(DataBarDecoder.decodeLimited(limited));
        assertNull(DataBarDecoder.decodeLimited(new boolean[40]));
        assertNull(DataBarDecoder.decodeLimited(null));
    }

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
}
