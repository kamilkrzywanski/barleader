package io.github.kamilkrzywanski.barleader.decode;

import com.google.zxing.oned.EAN13Writer;
import com.google.zxing.oned.EAN8Writer;
import com.google.zxing.oned.UPCEWriter;
import io.github.kamilkrzywanski.barleader.Symbology;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEanTest {
    private static final Set<Symbology> RETAIL = EnumSet.of(Symbology.EAN_13, Symbology.UPC_A, Symbology.EAN_8, Symbology.UPC_E);

    /**
     * The runs of {@code modules}, {@code module} samples a module, with {@code quiet} modules of
     * white before and after and every bar {@code wider} samples wider at the cost of the spaces.
     */
    private static float[] runs(boolean[] modules, float module, float quiet, float wider) {
        List<Float> out = new ArrayList<>();
        out.add(quiet * module);
        int from = 0;
        while (!modules[from]) {
            from++;
        }
        int to = modules.length;
        while (!modules[to - 1]) {
            to--;
        }
        int start = from;
        for (int i = from + 1; i <= to; i++) {
            if (i == to || modules[i] != modules[start]) {
                out.add((i - start) * module + (modules[start] ? wider : -wider));
                start = i;
            }
        }
        out.add(quiet * module);
        float[] array = new float[out.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = out.get(i);
        }
        return array;
    }

    private static List<RunEan.Read> decode(float[] runs, Set<Symbology> wanted) {
        return RunEan.decode(runs, runs.length, false, wanted);
    }

    @Test
    void readsEachRetailSymbology() {
        List<RunEan.Read> ean13 = decode(runs(new EAN13Writer().encode("590123412345"), 2.4f, 9, 0), RETAIL);
        assertEquals(1, ean13.size());
        assertEquals("5901234123457", ean13.get(0).text());
        assertEquals(Symbology.EAN_13, ean13.get(0).symbology());
        assertEquals(1, ean13.get(0).start());
        assertEquals(60, ean13.get(0).end());

        List<RunEan.Read> upca = decode(runs(new EAN13Writer().encode("001234567890"), 2.4f, 9, 0), RETAIL);
        assertEquals("012345678905", upca.get(0).text());
        assertEquals(Symbology.UPC_A, upca.get(0).symbology());
        assertEquals("0012345678905",
                decode(runs(new EAN13Writer().encode("001234567890"), 2.4f, 9, 0), EnumSet.of(Symbology.EAN_13)).get(0).text());

        List<RunEan.Read> ean8 = decode(runs(new EAN8Writer().encode("5512345"), 2.4f, 9, 0), RETAIL);
        assertEquals("55123457", ean8.get(0).text());
        assertEquals(Symbology.EAN_8, ean8.get(0).symbology());

        List<RunEan.Read> upce = decode(runs(new UPCEWriter().encode("0123456"), 2.4f, 9, 0), RETAIL);
        assertEquals(1, upce.size());
        assertEquals(Symbology.UPC_E, upce.get(0).symbology());
        assertEquals(8, upce.get(0).text().length());
        assertTrue(upce.get(0).text().startsWith("0123456"), upce.get(0).text());
    }

    /**
     * Bars that came out 0.9 samples wider than their spaces lost, or as much narrower, at 2.4
     * samples a module: the start guard shows the widening, and it is taken off the digits.
     */
    @Test
    void takesOffTheWideningOfTheBarsItMeasuresOnTheGuard() {
        boolean[] modules = new EAN13Writer().encode("590123412345");
        for (float wider : new float[]{0.9f, -0.9f}) {
            List<RunEan.Read> read = decode(runs(modules, 2.4f, 9, wider), RETAIL);
            assertEquals(1, read.size(), "wider " + wider);
            assertEquals("5901234123457", read.get(0).text());
        }
    }

    /** Runs of noise in front of the symbol: the search goes on from bar to bar until the guard with white before it. */
    @Test
    void findsASymbolBehindOtherRuns() {
        float[] symbol = runs(new EAN13Writer().encode("590123412345"), 3f, 9, 0);
        // White, bar, white, bar, white, bar, and the white in front of the symbol.
        float[] noise = {5, 2, 3, 7, 2, 2};
        float[] runs = new float[noise.length + symbol.length];
        System.arraycopy(noise, 0, runs, 0, noise.length);
        System.arraycopy(symbol, 0, runs, noise.length, symbol.length);
        List<RunEan.Read> read = RunEan.decode(runs, runs.length, false, RETAIL);
        assertEquals(1, read.size());
        assertEquals("5901234123457", read.get(0).text());
        assertEquals(noise.length + 1, read.get(0).start());
    }

    @Test
    void needsWhiteOnBothSidesAndTheFormatsAskedFor() {
        boolean[] modules = new EAN13Writer().encode("590123412345");
        assertTrue(decode(runs(modules, 2.4f, 9, 0), EnumSet.of(Symbology.EAN_8, Symbology.UPC_E)).isEmpty());
        assertTrue(decode(runs(modules, 2.4f, 9, 0), EnumSet.of(Symbology.CODE_128)).isEmpty());
        // Three modules of white after the symbol, with more runs behind them, are no quiet zone.
        float[] symbol = runs(modules, 2.4f, 3, 0);
        float[] runs = new float[symbol.length + 2];
        System.arraycopy(symbol, 0, runs, 0, symbol.length);
        runs[0] = 30;
        runs[symbol.length] = 5;
        runs[symbol.length + 1] = 30;
        assertTrue(RunEan.decode(runs, runs.length, false, RETAIL).isEmpty());
        // A module under 0.65 samples is not resolved.
        assertTrue(decode(runs(modules, 0.6f, 9, 0), RETAIL).isEmpty());
    }
}
