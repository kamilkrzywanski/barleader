package io.github.kamilkrzywanski.barcode.decode;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Locked to zxing-cpp {@code PatternTest}. A black edge is closed with a zero-width white run. */
class OneDRowTest {
    private static final int N = 33;

    @Test
    void matchesTheOfficialPatternRows() {
        for (int length = 1; length <= N; length++) {
            assertArrayEquals(new int[] {length}, OneDRow.runs(fill(length, false)), "white " + length);
            assertArrayEquals(new int[] {0, length, 0}, OneDRow.runs(fill(length, true)), "black " + length);
        }
        for (int black = 1; black <= N; black++) {
            boolean[] row = new boolean[N];
            Arrays.fill(row, 0, black, true);
            assertArrayEquals(new int[] {0, black, N - black}, OneDRow.runs(row), "black-white " + black);
        }
        for (int white = 0; white < N; white++) {
            boolean[] row = new boolean[N];
            Arrays.fill(row, white, N, true);
            assertArrayEquals(new int[] {white, N - white, 0}, OneDRow.runs(row), "white-black " + white);
        }
    }

    private static boolean[] fill(int length, boolean black) {
        boolean[] row = new boolean[length];
        if (black) {
            Arrays.fill(row, true);
        }
        return row;
    }
}
