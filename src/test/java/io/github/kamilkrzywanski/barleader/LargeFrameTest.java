package io.github.kamilkrzywanski.barleader;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frames of tens of megapixels: a small symbol on a blank canvas, and a symbol enlarged far past a pixel a module. */
class LargeFrameTest {
    private static final String[] L = {"0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111",
            "0111011", "0110111", "0001011"};
    private static final String[] PARITY = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL",
            "LGLGLG", "LGLGGL", "LGGLGL"};
    private static final String TEXT = "4006381333931";
    private static final int WIDTH = 6000;
    private static final int HEIGHT = 4000;

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

    private static void draw(byte[] luma, int left, int top, int module, int height) {
        String bars = modules(TEXT);
        for (int y = top; y < top + height; y++) {
            for (int m = 0; m < 95; m++) {
                if (bars.charAt(m) == '1') {
                    Arrays.fill(luma, y * WIDTH + left + m * module, y * WIDTH + left + (m + 1) * module, (byte) 20);
                }
            }
        }
    }

    @Test
    void aSmallSymbolOnABlankCanvasIsReadWhereItIs() {
        byte[] luma = new byte[WIDTH * HEIGHT];
        Arrays.fill(luma, (byte) 245);
        // A photo-sized patch of texture around the symbol, as a photo pasted on the canvas.
        Random random = new Random(7);
        for (int y = 2800; y < 3400; y++) {
            for (int x = 3800; x < 4700; x++) {
                luma[y * WIDTH + x] = (byte) (200 + random.nextInt(30));
            }
        }
        for (int y = 2950; y < 3250; y++) {
            Arrays.fill(luma, y * WIDTH + 3950, y * WIDTH + 4400, (byte) 240);
        }
        draw(luma, 4000, 3000, 3, 200);
        List<BarcodeHit> hits = new BarcodeReader().read(luma, WIDTH, HEIGHT, ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE));
        assertEquals(1, hits.size());
        assertEquals(TEXT, hits.get(0).text());
        for (ImagePoint corner : hits.get(0).corners()) {
            assertTrue(corner.x() > 3990 && corner.x() < 4300 && corner.y() > 2990 && corner.y() < 3210, corner.toString());
        }
    }

    @Test
    void aSymbolEnlargedFarPastAPixelAModuleReadsOnTheReducedCopy() {
        byte[] luma = new byte[WIDTH * HEIGHT];
        Random random = new Random(11);
        for (int i = 0; i < luma.length; i++) {
            luma[i] = (byte) (205 + random.nextInt(40));
        }
        draw(luma, 600, 400, 50, 3000);
        List<BarcodeHit> hits = new BarcodeReader().read(luma, WIDTH, HEIGHT, ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE));
        assertEquals(1, hits.size());
        assertEquals(TEXT, hits.get(0).text());
        assertTrue(hits.get(0).corners().get(0).x() < 1000 && hits.get(0).corners().get(1).x() > 4800, hits.get(0).corners().toString());
    }
}
