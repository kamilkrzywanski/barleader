package org.krzywanski.barcode.decode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Synthetic DX Film Edge symbols. Bits are the layout in {@code ODDXFilmEdgeReader.cpp}:
 * a white separator, a 7-bit product, a separator, a 4-bit generation, then either a parity bit
 * and a separator or a 6-bit frame, the half-frame flag, a separator, parity, and a separator.
 */
class DxFilmEdgeDecoderTest {
    private static final String FRAME = "01110011010100010111010";
    private static final String FRAME_WITHOUT_HALF = "01110011010100010110000";
    private static final String PLAIN = "011100110101010";
    private static final String SAMPLE = "000010100001100";
    private static final String GENERATION_ZERO = "000000010000010";
    private static final String PRODUCT_ZERO = "000000000000110";
    private static final String BAD_PARITY = "01110011010100010111000";
    private static final String BLACK_SEPARATOR = "01110011110100010111000";

    @Test
    void readsTheClockThenTheDataTrack() {
        assertEquals("115-10/11A", read(2, 11, 5, 7, FRAME, true, 0));
        assertEquals("115-10/11A", read(1, 11, 5, 7, FRAME, true, 0));
        assertEquals("115-10/11", read(1, 11, 5, 7, FRAME_WITHOUT_HALF, true, 0));
        assertEquals("115-10", read(1, 11, 5, 7, PLAIN, false, 0));
        assertEquals("10-3", read(1, 11, 5, 7, SAMPLE, false, 0));
        assertEquals("1-0", read(1, 11, 5, 7, GENERATION_ZERO, false, 0));
        // The row immediately above the center is still accepted.
        assertEquals("115-10/11A", read(1, 11, 4, 6, FRAME, true, 0));
        // This mirror is not itself a symbol, so only the reversed run recovers it.
        assertEquals("115-10/11", DxFilmEdgeDecoder.decode(flipped(image(1, 11, 5, 7, FRAME_WITHOUT_HALF, true, 0))));
    }

    @Test
    void rejectsATrackThatIsNotBesideItsClock() {
        // A clock by itself is not a symbol, including when that row is reversed.
        assertNull(read(1, 11, 5, 7, null, true, 0));
        assertNull(read(1, 11, 3, 6, FRAME, true, 0));
        assertNull(read(1, 32, 16, 23, FRAME, true, 0));
        assertNull(read(1, 11, 5, 7, FRAME, true, 2));
        assertNull(read(1, 11, 5, 7, PLAIN, true, 0));
        assertNull(read(1, 11, 5, 7, PRODUCT_ZERO, false, 0));
        assertNull(read(1, 11, 5, 7, BAD_PARITY, true, 0));
        assertNull(read(1, 11, 5, 7, BLACK_SEPARATOR, true, 0));
        assertNull(DxFilmEdgeDecoder.decode(null));
        assertNull(DxFilmEdgeDecoder.decode(new boolean[0][]));
        assertNull(DxFilmEdgeDecoder.decode(new boolean[8][8]));
    }

    private static String read(int module, int height, int clockRow, int dataRow, String bits, boolean frame, int shift) {
        return DxFilmEdgeDecoder.decode(image(module, height, clockRow, dataRow, bits, frame, shift));
    }

    private static boolean[][] image(int module, int height, int clockRow, int dataRow, String bits, boolean frame, int shift) {
        int quiet = 4 * module;
        boolean[] clock = paint(module, quiet, clockModules(frame));
        boolean[] data = bits == null ? null : paint(module, quiet + shift, dataModules(bits));
        int width = clock.length;
        if (data != null) {
            width = Math.max(width, data.length);
        }
        boolean[][] image = new boolean[height][width];
        System.arraycopy(clock, 0, image[clockRow], 0, clock.length);
        if (data != null && dataRow != clockRow) {
            System.arraycopy(data, 0, image[dataRow], 0, data.length);
        }
        return image;
    }

    private static boolean[][] flipped(boolean[][] image) {
        boolean[][] copy = new boolean[image.length][];
        for (int y = 0; y < image.length; y++) {
            copy[y] = image[y].clone();
            for (int i = 0, j = copy[y].length - 1; i < j; i++, j--) {
                boolean swap = copy[y][i];
                copy[y][i] = copy[y][j];
                copy[y][j] = swap;
            }
        }
        return copy;
    }

    /** Five-module start, the bit runs, and the three-module stop. The bit string starts on white. */
    private static int[] dataModules(String bits) {
        int[] payload = group(bits);
        int[] modules = new int[5 + payload.length + 3];
        for (int i = 0; i < 5; i++) {
            modules[i] = 1;
        }
        System.arraycopy(payload, 0, modules, 5, payload.length);
        modules[modules.length - 3] = 1;
        modules[modules.length - 2] = 1;
        modules[modules.length - 1] = 1;
        return modules;
    }

    private static int[] clockModules(boolean frame) {
        int ones = frame ? 23 : 15;
        int[] modules = new int[ones + 2];
        modules[0] = 5;
        for (int i = 1; i <= ones; i++) {
            modules[i] = 1;
        }
        modules[ones + 1] = 3;
        return modules;
    }

    private static int[] group(String bits) {
        List<Integer> runs = new ArrayList<>();
        int cursor = 0;
        while (cursor < bits.length()) {
            char bit = bits.charAt(cursor);
            int width = 0;
            while (cursor < bits.length() && bits.charAt(cursor) == bit) {
                width++;
                cursor++;
            }
            runs.add(width);
        }
        int[] modules = new int[runs.size()];
        for (int i = 0; i < modules.length; i++) {
            modules[i] = runs.get(i);
        }
        return modules;
    }

    private static boolean[] paint(int module, int quiet, int[] modules) {
        int span = 0;
        for (int value : modules) {
            span += value;
        }
        boolean[] row = new boolean[quiet + span * module + quiet];
        int x = quiet;
        boolean black = true;
        for (int value : modules) {
            int pixels = value * module;
            for (int i = 0; i < pixels; i++) {
                row[x++] = black;
            }
            black = !black;
        }
        return row;
    }
}
