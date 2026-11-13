package io.github.kamilkrzywanski.barleader.retail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunWindowsTest {
    private static RunWindows.Runs runs(float[] row) {
        RunWindows.Runs runs = new RunWindows.Runs();
        RunWindows.runs(row, row.length, runs);
        return runs;
    }

    @Test
    void readsASymbolBetweenItsQuietZonesAndTellsWhereItLies() {
        float[] row = Bars.row(Bars.modules(Bars.TEXT), 512, 4, 16);
        RunWindows.Runs runs = runs(row);
        assertEquals(61, runs.count);
        RunWindows.Reads reads = RunWindows.decode(runs, false);
        assertEquals(1, reads.symbols.size());
        RunWindows.Read read = reads.symbols.get(0);
        assertArrayEquals(Bars.DIGITS, read.digits());
        assertFalse(read.reversed());
        assertEquals(64 - 0.5, read.left(), 1e-9);
        assertEquals(64 + 95 * 4 - 0.5, read.right(), 1e-9);
        assertEquals(1, reads.guardPass);
        assertTrue(reads.rejected.isEmpty());
    }

    @Test
    void readsARowTakenAgainstTheSymbol() {
        float[] row = Bars.row(Bars.modules(Bars.TEXT), 512, 4, 16);
        float[] back = new float[row.length];
        for (int i = 0; i < row.length; i++) {
            back[i] = row[row.length - 1 - i];
        }
        RunWindows.Reads reads = RunWindows.decode(runs(back), false);
        assertEquals(1, reads.symbols.size());
        assertArrayEquals(Bars.DIGITS, reads.symbols.get(0).digits());
        assertTrue(reads.symbols.get(0).reversed());
    }

    /** Five modules of white before and after: no symbol between full quiet zones, one between short ones. */
    @Test
    void aSymbolBetweenShortQuietZonesIsReadOnlyAsSuch() {
        boolean[] modules = Bars.modules(Bars.TEXT);
        float[] row = new float[512];
        float[] symbol = Bars.row(modules, 5 * 4 + 95 * 4 + 5 * 4, 4, 5);
        java.util.Arrays.fill(row, 1f);
        System.arraycopy(symbol, 0, row, 40, symbol.length);
        RunWindows.Runs runs = runs(row);
        assertTrue(RunWindows.decode(runs, false).symbols.isEmpty());
        RunWindows.Reads reads = RunWindows.shortQuiet(runs, true);
        assertEquals(1, reads.symbols.size());
        assertArrayEquals(Bars.DIGITS, reads.symbols.get(0).digits());
        // Between full quiet zones the short rule does not read: that reading is the ordinary one.
        assertTrue(RunWindows.shortQuiet(runs(Bars.row(modules, 512, 4, 16)), true).symbols.isEmpty());
    }

    /** Two ways to cut one row that read different digits over one stretch refuse each other, and the stretch is kept as rejected. */
    @Test
    void differentReadingsOfOneStretchRefuseEachOther() {
        RunWindows.Reads one = new RunWindows.Reads();
        one.symbols.add(new RunWindows.Read(Bars.DIGITS, 60, 440, 0.01f, 0.3f, false));
        RunWindows.Reads other = new RunWindows.Reads();
        int[] digits = Bars.DIGITS.clone();
        digits[5] = 8;
        other.symbols.add(new RunWindows.Read(digits, 62, 441, 0.02f, 0.2f, false));
        RunWindows.Reads merged = RunWindows.merge(one, other);
        assertTrue(merged.symbols.isEmpty());
        assertEquals(2, merged.rejected.size());
        // The same digits over the same stretch are one reading; over another stretch they are a second symbol.
        RunWindows.Reads same = new RunWindows.Reads();
        same.symbols.add(new RunWindows.Read(Bars.DIGITS, 61, 441, 0.02f, 0.2f, false));
        same.symbols.add(new RunWindows.Read(Bars.DIGITS, 600, 980, 0.02f, 0.2f, false));
        assertEquals(2, RunWindows.merge(one, same).symbols.size());
        // What one cut rejected refuses the other cut's reading there too.
        assertTrue(RunWindows.merge(merged, same).symbols.stream().allMatch(read -> read.left() == 600));
    }
}
