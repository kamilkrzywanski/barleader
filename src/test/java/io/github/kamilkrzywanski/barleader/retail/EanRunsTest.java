package io.github.kamilkrzywanski.barleader.retail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EanRunsTest {
    @Test
    void readsTheWidthsOfASymbolWithItsFirstDigitFromTheMixOfTheLeftDigits() {
        EanRuns.Evidence evidence = EanRuns.visual(Bars.widths(Bars.modules(Bars.TEXT), 2.4f, 0));
        assertNotNull(evidence);
        assertArrayEquals(Bars.DIGITS, evidence.digits());
        assertEquals(0f, evidence.cost(), 1e-4f);
        assertTrue(evidence.gap() > 0.3f, "gap " + evidence.gap());
        assertTrue(EanRuns.checksum(evidence.digits()));
        assertFalse(EanRuns.checksum(new int[]{5, 9, 0, 1, 2, 3, 4, 1, 2, 3, 4, 5, 8}));
    }

    /** The modules of a text are those its widths add up to: guards, six left digits by the first digit's mix, six right. */
    @Test
    void drawsTheModulesOfAText() {
        boolean[] expected = Bars.modules(Bars.TEXT);
        float[] modules = EanRuns.modules(Bars.DIGITS);
        assertEquals(95, modules.length);
        for (int i = 0; i < 95; i++) {
            assertEquals(expected[i] ? 1f : 0f, modules[i], "module " + i);
        }
    }

    /** Runs of one width each are no digits, and a symbol under 0.8 samples a module is not resolved. */
    @Test
    void refusesWhatDoesNotLookLikeASymbol() {
        float[] equal = new float[59];
        java.util.Arrays.fill(equal, 3f);
        assertNull(EanRuns.visual(equal));
        assertNull(EanRuns.visual(Bars.widths(Bars.modules(Bars.TEXT), 0.7f, 0)));
        float[] widths = Bars.widths(Bars.modules(Bars.TEXT), 2.4f, 0);
        // A middle guard bar of three modules is no guard.
        widths[28] = 7.2f;
        assertNull(EanRuns.visual(widths));
    }

    /**
     * Bars 1.1 samples wider than printed at three samples a module, at the cost of the spaces,
     * or as much narrower: the widths alone read nothing, and with the widening that the guards
     * show taken out they read.
     */
    @Test
    void takesOutTheWideningOfTheBarsMeasuredOnTheGuards() {
        boolean[] modules = Bars.modules(Bars.TEXT);
        for (float wider : new float[]{1.1f, -1.1f}) {
            float[] widths = Bars.widths(modules, 3f, wider);
            assertNull(EanRuns.visual(widths), "wider " + wider);
            float[] corrected = EanRuns.guardBias(widths);
            assertNotNull(corrected, "wider " + wider);
            assertArrayEquals(Bars.widths(modules, 3f, 0), corrected, 1e-3f);
            assertArrayEquals(Bars.DIGITS, EanRuns.visual(corrected).digits());
        }
        // Guards that show no widening leave the widths alone.
        assertNull(EanRuns.guardBias(Bars.widths(modules, 3f, 0.05f)));
    }

    /** Bars that widen from the start of the symbol to its end: one amount does not fit the guards, one for each guard group does. */
    @Test
    void followsAWideningThatChangesAlongTheSymbol() {
        boolean[] modules = Bars.modules(Bars.TEXT);
        float[] widths = Bars.widths(modules, 3f, 0);
        float[] centers = new float[59];
        float at = 0;
        for (int i = 0; i < 59; i++) {
            centers[i] = at + widths[i] / 2;
            at += widths[i];
        }
        for (int i = 0; i < 59; i++) {
            float wider = 1.1f * centers[i] / at;
            widths[i] += i % 2 == 0 ? wider : -wider;
        }
        float[] corrected = EanRuns.guardBias(widths);
        assertNotNull(corrected);
        EanRuns.Evidence evidence = EanRuns.visual(corrected);
        assertNotNull(evidence);
        assertArrayEquals(Bars.DIGITS, evidence.digits());
    }
}
