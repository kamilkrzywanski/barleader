package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EanCrossCheckTest {
    private static final String[] L = {"0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111",
            "0111011", "0110111", "0001011"};
    private static final String[] PARITY = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL",
            "LGLGLG", "LGLGGL", "LGGLGL"};
    private static final int MODULE = 3;
    private static final int QUIET = 40;

    private static GrayU8 symbol(String text) {
        StringBuilder bars = new StringBuilder("101");
        String parity = PARITY[text.charAt(0) - '0'];
        for (int i = 1; i <= 6; i++) {
            String l = L[text.charAt(i) - '0'];
            if (parity.charAt(i - 1) == 'G') {
                StringBuilder g = new StringBuilder();
                for (int k = 6; k >= 0; k--) {
                    g.append(l.charAt(k) == '0' ? '1' : '0');
                }
                l = g.toString();
            }
            bars.append(l);
        }
        bars.append("01010");
        for (int i = 7; i <= 12; i++) {
            for (char c : L[text.charAt(i) - '0'].toCharArray()) {
                bars.append(c == '0' ? '1' : '0');
            }
        }
        bars.append("101");
        int width = 95 * MODULE + 2 * QUIET;
        int height = 200;
        GrayU8 gray = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int m = (x - QUIET) / MODULE;
                boolean bar = x >= QUIET && m < 95 && bars.charAt(m) == '1';
                gray.data[y * width + x] = (byte) (bar ? 25 : 225);
            }
        }
        return gray;
    }

    private static EanCrossCheck.Result check(GrayU8 gray, String text) {
        return EanCrossCheck.check(gray, QUIET, 100, QUIET + 95 * MODULE - 1, 100, text);
    }

    @Test
    void aReadTheImageRepeatsIsKept() {
        GrayU8 gray = symbol("4006381333931");
        assertEquals(EanCrossCheck.Verdict.KEEP, check(gray, "4006381333931").verdict());
    }

    @Test
    void aReadWithAWrongLeftHalfTakesTheTextTheLinesAgreeOn() {
        GrayU8 gray = symbol("4006381333931");
        EanCrossCheck.Result result = check(gray, "7001891333931");
        assertEquals(EanCrossCheck.Verdict.REPLACE, result.verdict());
        assertEquals("4006381333931", result.text());
    }

    @Test
    void theLinesBesideARightReadRepeatItAndThoseBesideAWrongOneDoNot() {
        GrayU8 gray = symbol("4006381333931");
        assertTrue(EanCrossCheck.repeated(gray, QUIET, 100, QUIET + 95 * MODULE - 1, 100, "4006381333931"));
        assertFalse(EanCrossCheck.repeated(gray, QUIET, 100, QUIET + 95 * MODULE - 1, 100, "7001891333931"));
        assertTrue(EanCrossCheck.repeated(gray, QUIET, 100, QUIET + 10, 100, "7001891333931"), "too short to tell");
    }

    @Test
    void aReadWithoutAnyEanOnTheLinesIsKept() {
        GrayU8 blank = new GrayU8(400, 200);
        java.util.Arrays.fill(blank.data, (byte) 200);
        assertEquals(EanCrossCheck.Verdict.KEEP, EanCrossCheck.check(blank, 40, 100, 324, 100, "4006381333931").verdict());
    }
}
