package io.github.kamilkrzywanski.barleader.retail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortWindowsTest {
    private static final String TEXT = "96385074";
    private static final int[] DIGITS = {ShortWindows.EAN8, 0, 0, 0, 0, 9, 6, 3, 8, 5, 0, 7, 4};

    private static RunWindows.Runs runs(float[] row) {
        RunWindows.Runs runs = new RunWindows.Runs();
        RunWindows.runs(row, row.length, runs);
        return runs;
    }

    private static ShortWindows.Reads decode(RunWindows.Runs runs, RunWindows.Reads primary) {
        ShortWindows.Reads out = new ShortWindows.Reads();
        ShortWindows.decode(runs, primary, out);
        return out;
    }

    @Test
    void readsAnEan8BetweenItsQuietZonesAndTellsWhereItLies() {
        float[] row = Bars.row(Bars.modules8(TEXT), 400, 4, 12);
        RunWindows.Runs runs = runs(row);
        assertEquals(45, runs.count);
        ShortWindows.Reads reads = decode(runs, new RunWindows.Reads());
        assertEquals(1, reads.symbols.size());
        RunWindows.Read read = reads.symbols.get(0);
        assertArrayEquals(DIGITS, read.digits());
        assertFalse(read.reversed());
        assertEquals(48 - 0.5, read.left(), 1e-9);
        assertEquals(48 + 67 * 4 - 0.5, read.right(), 1e-9);
        assertTrue(reads.conflicts.isEmpty());
    }

    @Test
    void readsARowTakenAgainstTheSymbol() {
        float[] row = Bars.row(Bars.modules8(TEXT), 400, 4, 12);
        float[] back = new float[row.length];
        for (int i = 0; i < row.length; i++) {
            back[i] = row[row.length - 1 - i];
        }
        ShortWindows.Reads reads = decode(runs(back), new RunWindows.Reads());
        assertEquals(1, reads.symbols.size());
        assertArrayEquals(DIGITS, reads.symbols.get(0).digits());
        assertTrue(reads.symbols.get(0).reversed());
    }

    /** Less than two modules of white on one side: the profile did not see where the symbol ends. */
    @Test
    void needsTwoModulesOfWhiteOnBothSides() {
        boolean[] modules = Bars.modules8(TEXT);
        float[] row = new float[400];
        java.util.Arrays.fill(row, 1f);
        float[] symbol = Bars.row(modules, 4 + 67 * 4 + 40, 4, 1);
        System.arraycopy(symbol, 0, row, 40, symbol.length);
        assertTrue(decode(runs(row), new RunWindows.Reads()).symbols.isEmpty());
    }

    /** Bars a module and a half wide at the cost of the spaces: no digit reads as it is, and the guards tell by how much to narrow them. */
    @Test
    void takesOutTheWideningThatTheGuardsShow() {
        boolean[] modules = Bars.modules8(TEXT);
        RunWindows.Runs runs = new RunWindows.Runs();
        runs.add(0, 60, false);
        double x = 60;
        int start = 0;
        for (int i = 1; i <= modules.length; i++) {
            if (i == modules.length || modules[i] != modules[start]) {
                double width = (i - start) * 6 + (modules[start] ? 3 : -3);
                runs.add(x, x + width, modules[start]);
                x += width;
                start = i;
            }
        }
        runs.add(x, x + 60, false);
        ShortWindows.Reads reads = decode(runs, new RunWindows.Reads());
        assertEquals(1, reads.symbols.size());
        assertArrayEquals(DIGITS, reads.symbols.get(0).digits());
    }

    /** Where an EAN-13 read, or its readings refused each other, no EAN-8 is looked for. */
    @Test
    void leavesTheStretchesOfTheLongSymbolsAlone() {
        float[] row = Bars.row(Bars.modules8(TEXT), 400, 4, 12);
        RunWindows.Runs runs = runs(row);
        RunWindows.Reads read = new RunWindows.Reads();
        read.symbols.add(new RunWindows.Read(Bars.DIGITS, 200, 380, 0.01f, 0.5f, false));
        assertTrue(decode(runs, read).symbols.isEmpty());
        RunWindows.Reads rejected = new RunWindows.Reads();
        rejected.rejected.add(new double[]{20, 60});
        assertTrue(decode(runs, rejected).symbols.isEmpty());
        // An EAN-13 that covers every bar of the row leaves nothing to read.
        RunWindows.Reads whole = new RunWindows.Reads();
        whole.symbols.add(new RunWindows.Read(Bars.DIGITS, 40, 320, 0.01f, 0.5f, false));
        assertTrue(ShortWindows.covered(runs, whole));
    }

    /** Two readings of other digits over one stretch refuse each other, and the stretch takes no reading after them. */
    @Test
    void twoTextsOverOneStretchRefuseEachOther() {
        ShortWindows.Reads reads = new ShortWindows.Reads();
        int[] other = DIGITS.clone();
        other[12] = 1;
        ShortWindows.insert(reads, new RunWindows.Read(DIGITS, 10, 100, 0.05f, 0.2f, false));
        ShortWindows.insert(reads, new RunWindows.Read(DIGITS, 12, 102, 0.03f, 0.2f, false));
        assertEquals(1, reads.symbols.size());
        assertEquals(0.03f, reads.symbols.get(0).cost());
        ShortWindows.insert(reads, new RunWindows.Read(other, 50, 140, 0.02f, 0.2f, false));
        assertTrue(reads.symbols.isEmpty());
        assertEquals(1, reads.conflicts.size());
        ShortWindows.insert(reads, new RunWindows.Read(DIGITS, 10, 100, 0.01f, 0.2f, false));
        assertTrue(reads.symbols.isEmpty());
    }

    /** A row whose light level drifts: cut at one level it loses bars, cut between its peaks and valleys it reads. */
    @Test
    void readsARowOfDriftingLevelsBetweenItsPeaksAndValleys() {
        float[] row = Bars.row(Bars.modules8(TEXT), 400, 4, 12);
        float[] drifting = new float[row.length];
        for (int i = 0; i < row.length; i++) {
            // Dark stays 0.35 above light, and both rise by 0.6 along the row.
            drifting[i] = 0.05f + 0.6f * i / row.length + 0.35f * row[i];
        }
        assertTrue(decode(runs(drifting), new RunWindows.Reads()).symbols.isEmpty());
        RunWindows.Runs peaks = new RunWindows.Runs();
        ShortWindows.peakRuns(drifting, drifting.length, peaks);
        ShortWindows.Reads reads = decode(peaks, new RunWindows.Reads());
        assertEquals(1, reads.symbols.size());
        assertArrayEquals(DIGITS, reads.symbols.get(0).digits());
    }
}
