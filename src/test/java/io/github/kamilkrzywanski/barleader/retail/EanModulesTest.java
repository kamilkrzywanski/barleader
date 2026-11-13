package io.github.kamilkrzywanski.barleader.retail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EanModulesTest {
    /** The share of a unit Gaussian below {@code x}. */
    private static double below(double x) {
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double tail = Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI)
                * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return x >= 0 ? 1 - tail : tail;
    }

    /** A row of 512 samples with the symbol at four samples a module from sample 66, blurred by {@code sigma} modules. */
    private static float[] blurred(boolean[] modules, double sigma) {
        float[] row = new float[512];
        for (int i = 0; i < row.length; i++) {
            double x = (i + 0.5 - 66) / 4;
            double dark = 0;
            for (int m = 0; m < modules.length; m++) {
                if (modules[m]) {
                    dark += below((m + 1 - x) / sigma) - below((m - x) / sigma);
                }
            }
            row[i] = (float) Math.max(0, Math.min(1, dark));
        }
        return row;
    }

    @Test
    void readsSharpModulesEitherWay() {
        float[] row = Bars.row(Bars.modules(Bars.TEXT), 512, 4, 16.5);
        EanModules.Trace trace = new EanModules.Trace();
        EanModules.Read read = EanModules.decode(row, row.length, trace);
        assertNotNull(read);
        assertArrayEquals(Bars.DIGITS, read.digits());
        assertFalse(read.reversed());
        assertEquals(65.5f, read.left(), 0.01f);
        assertEquals(445.5f, read.right(), 0.01f);
        assertTrue(trace.rejected.isEmpty());
        float[] back = new float[row.length];
        for (int i = 0; i < row.length; i++) {
            back[i] = row[row.length - 1 - i];
        }
        EanModules.Read reversed = EanModules.decode(back, back.length, new EanModules.Trace());
        assertNotNull(reversed);
        assertArrayEquals(Bars.DIGITS, reversed.digits());
        assertTrue(reversed.reversed());
    }

    /**
     * Bars blurred by 0.65 of a module: single modules no longer reach the middle level, so the
     * row has too few runs for a symbol, and the modules against blurred digits read it.
     */
    @Test
    void readsModulesTooBlurredToCountAsRuns() {
        float[] row = blurred(Bars.modules(Bars.TEXT), 0.65);
        RunWindows.Runs runs = new RunWindows.Runs();
        RunWindows.runs(row, row.length, runs);
        assertTrue(RunWindows.decode(runs, false).symbols.isEmpty());
        EanModules.Trace trace = new EanModules.Trace();
        EanModules.Read read = EanModules.decode(row, row.length, trace);
        assertNotNull(read);
        assertArrayEquals(Bars.DIGITS, read.digits());
        assertTrue(trace.blurWindows > 0);
    }

    @Test
    void aRowWithoutASymbolReadsNothing() {
        float[] flat = new float[512];
        assertNull(EanModules.decode(flat, flat.length, new EanModules.Trace()));
        float[] stripes = new float[512];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = i / 6 % 2 == 0 ? 1f : 0f;
        }
        EanModules.Trace trace = new EanModules.Trace();
        assertNull(EanModules.decode(stripes, stripes.length, trace));
        assertEquals(0, trace.blurWindows);
    }
}
