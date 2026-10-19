package io.github.kamilkrzywanski.barleader.image;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrayImagesTest {
    @Test
    void horizontalUnsharpDeepensANarrowDipAndLeavesAFlatRow() {
        GrayU8 gray = new GrayU8(7, 1);
        for (int x = 0; x < gray.width; x++) {
            gray.set(x, 0, 200);
        }
        gray.set(3, 0, 50);
        GrayU8 sharp = GrayImages.unsharpHorizontal(gray, 1, 2.0);
        assertEquals(200, sharp.get(0, 0));
        assertEquals(200, sharp.get(1, 0));
        assertEquals(255, sharp.get(2, 0));
        assertEquals(0, sharp.get(3, 0));
        assertEquals(255, sharp.get(4, 0));

        GrayU8 flat = new GrayU8(4, 2);
        for (int y = 0; y < flat.height; y++) {
            for (int x = 0; x < flat.width; x++) {
                flat.set(x, y, 128);
            }
        }
        GrayU8 same = GrayImages.unsharpHorizontal(flat, 2, 3.0);
        assertEquals(128, same.get(1, 1));
        assertEquals(128, same.get(3, 0));
    }

    @Test
    void median3MatchesTheClampedWindowSort() {
        Random random = new Random(7);
        for (int height : new int[]{1, 2, 3, 5, 17}) {
            for (int width : new int[]{1, 2, 3, 8, 19}) {
                GrayU8 gray = new GrayU8(width, height);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        gray.set(x, y, random.nextInt(256));
                    }
                }
                GrayU8 median = GrayImages.median3(gray);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        assertEquals(referenceMedian(gray, x, y), median.get(x, y), width + "x" + height);
                    }
                }
            }
        }
    }

    /** The previous per-pixel clamp and insertion sort, kept as the oracle. */
    private static int referenceMedian(GrayU8 gray, int x, int y) {
        int[] window = new int[9];
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = y + dy;
            if (yy < 0) {
                yy = 0;
            } else if (yy >= gray.height) {
                yy = gray.height - 1;
            }
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx;
                if (xx < 0) {
                    xx = 0;
                } else if (xx >= gray.width) {
                    xx = gray.width - 1;
                }
                window[n++] = gray.get(xx, yy);
            }
        }
        for (int i = 1; i < window.length; i++) {
            int value = window[i];
            int j = i - 1;
            while (j >= 0 && window[j] > value) {
                window[j + 1] = window[j];
                j--;
            }
            window[j + 1] = value;
        }
        return window[4];
    }

    @Test
    void horizontalUnsharpRejectsANonPositiveRadius() {
        assertThrows(IllegalArgumentException.class, () -> GrayImages.unsharpHorizontal(new GrayU8(4, 1), 0, 1.0));
    }
}
