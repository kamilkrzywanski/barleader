package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;
import io.github.kamilkrzywanski.barleader.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EanHalvesTest {
    private static final String[] L = {"0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111",
            "0111011", "0110111", "0001011"};
    private static final String[] PARITY = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL",
            "LGLGLG", "LGLGGL", "LGGLGL"};
    private static final int MODULE = 4;
    private static final int QUIET = 15 * MODULE;

    private static String modules(String text) {
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
        return bars.append("101").toString();
    }

    /**
     * Glare over the right half of the upper rows and over the left half of the lower ones: no row
     * holds the whole symbol, each holds one half. {@code glare} picks the modules a band hides.
     */
    private static GrayU8 glared(String bars, int fromModule, int toModule) {
        int width = 95 * MODULE + 2 * QUIET;
        int height = 120;
        GrayU8 gray = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            boolean upper = y < height / 2;
            for (int x = 0; x < width; x++) {
                int m = (x - QUIET) / MODULE;
                boolean inside = x >= QUIET && m < 95;
                boolean hidden = inside && (upper ? m >= 48 + fromModule && m < 48 + toModule : m >= 3 + fromModule && m < 3 + toModule);
                boolean bar = inside && !hidden && bars.charAt(m) == '1';
                gray.data[y * width + x] = (byte) (hidden ? 250 : bar ? 25 : 225);
            }
        }
        return gray;
    }

    @Test
    void halvesOnDifferentRowsJoin() {
        String text = "4006381333931";
        GrayU8 gray = glared(modules(text), 5, 30);
        BinaryBitmap bitmap = BinaryBitmap.hybrid(new GrayLuminanceSource(gray));
        assertNull(UpcEanScanner.read(bitmap, EnumSet.of(Symbology.EAN_13)), "no row holds the whole symbol");
        EanHalves.Found found = EanHalves.read(bitmap, 1);
        assertNotNull(found);
        assertEquals(text, found.text());
    }

    @Test
    void repeatingBarsDoNotJoin() {
        // Bars of one width read as halves of zeros and fives; no text may come of them.
        int width = 600;
        int height = 80;
        GrayU8 gray = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                gray.data[y * width + x] = (byte) (x > 60 && x < 540 && (x / 4) % 2 == 0 ? 25 : 225);
            }
        }
        assertNull(EanHalves.read(BinaryBitmap.hybrid(new GrayLuminanceSource(gray)), 1));
    }
}
