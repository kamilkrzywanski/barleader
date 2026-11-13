package io.github.kamilkrzywanski.barleader.retail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalModelsTest {
    private static RunWindows.Runs runs(float[] row) {
        RunWindows.Runs runs = new RunWindows.Runs();
        RunWindows.runs(row, row.length, runs);
        return runs;
    }

    /** One light sample inside a wide bar, three samples from its end, splits it in three runs: the cluster beside the edge is taken back. */
    @Test
    void joinsASpeckBesideAnEdgeToTheRunAroundIt() {
        float[] row = Bars.row(Bars.modules(Bars.TEXT), 1024, 8, 16);
        RunWindows.Runs clean = runs(row);
        int wide = -1;
        for (int i = 1; i < clean.count && wide < 0; i++) {
            if (clean.black[i] && clean.end[i] - clean.start[i] == 32) {
                wide = i;
            }
        }
        assertTrue(wide > 0);
        row[(int) clean.end[wide] - 4] = 0.4f;
        RunWindows.Runs runs = runs(row);
        assertEquals(63, runs.count);
        RunWindows.Reads raw = RunWindows.decode(runs, false);
        assertTrue(raw.symbols.isEmpty());
        RunWindows.Reads cleaned = SignalModels.clustered(row, row.length, runs, raw);
        assertEquals(1, cleaned.symbols.size());
        assertArrayEquals(Bars.DIGITS, cleaned.symbols.get(0).digits());
        // Runs with no such cluster give back what they read as they are.
        RunWindows.Runs untouched = runs(Bars.row(Bars.modules(Bars.TEXT), 1024, 8, 16));
        RunWindows.Reads read = RunWindows.decode(untouched, false);
        assertEquals(read.symbols, SignalModels.clustered(row, row.length, untouched, read).symbols);
    }

    /**
     * A symbol whose first third is washed out: its spaces lie just above the middle level and
     * join the bars. The level halfway between each peak and valley still finds every edge, and
     * so do levels that follow the row.
     */
    @Test
    void readsARowWhoseLevelsChangeAlongIt() {
        boolean[] modules = Bars.modules(Bars.TEXT);
        float[] row = Bars.row(modules, 1024, 8, 16);
        for (int i = 0; i < row.length; i++) {
            double m = (i + 0.5) / 8 - 16;
            if (m >= 6 && m < 30) {
                row[i] = row[i] > 0.5f ? 1f : 0.6f;
            }
        }
        RunWindows.Runs runs = runs(row);
        assertTrue(runs.count >= 45 && runs.count < 61, "runs " + runs.count);
        assertTrue(RunWindows.decode(runs, false).symbols.isEmpty());
        RunWindows.Reads extrema = SignalModels.extrema(row, row.length, false);
        assertEquals(1, extrema.symbols.size());
        assertArrayEquals(Bars.DIGITS, extrema.symbols.get(0).digits());
        SignalModels.Local scratch = new SignalModels.Local();
        RunWindows.Reads local = SignalModels.local(row, row.length, true, scratch);
        assertEquals(1, local.symbols.size());
        assertArrayEquals(Bars.DIGITS, local.symbols.get(0).digits());
        // The runs of the row with its levels following it stay for the readings between short quiet zones: here there are none.
        assertEquals(scratch.integer.count, scratch.fractional.count);
        assertTrue(SignalModels.preparedShort(scratch, true).symbols.isEmpty());
    }

    /** Fewer than 45 runs are no symbol for the extrema, and a flat row has no turns. */
    @Test
    void theExtremaLeaveRowsWithoutBarsAlone() {
        float[] flat = new float[512];
        java.util.Arrays.fill(flat, 0.3f);
        assertTrue(SignalModels.extrema(flat, flat.length, true).symbols.isEmpty());
        assertTrue(SignalModels.local(flat, flat.length, true, new SignalModels.Local()).symbols.isEmpty());
    }
}
