package io.github.kamilkrzywanski.barleader.preprocess;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ThresholdsTest {
    /** Dark bars on a background that brightens from left to right, as under a lamp at one side. */
    private static GrayU8 barsUnderUnevenLight(int width, int height) {
        GrayU8 gray = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int light = 90 + 150 * x / (width - 1);
                boolean bar = (x / 8) % 2 == 0 && y > height / 4 && y < 3 * height / 4;
                gray.set(x, y, bar ? light / 3 : light);
            }
        }
        return gray;
    }

    @Test
    void gaussianFollowsTheLightAcrossTheFrame() {
        GrayU8 gray = barsUnderUnevenLight(480, 240);
        GrayU8 binary = Thresholds.gaussian(gray);
        // A bar in the dim left half and one in the bright right half are both dark, and the
        // background beside them, darker on the left than the bright bars, is light.
        assertEquals(1, binary.get(20, 120));
        assertEquals(1, binary.get(452, 120));
        assertEquals(0, binary.get(28, 120));
        assertEquals(0, binary.get(460, 120));
        assertEquals(0, binary.get(20, 20));
    }

    /** The kernel applied tap by tap, as the threshold did before it was summed by boxes. */
    private static GrayU8 tapByTap(GrayU8 input, int[] kernel, boolean horizontal) {
        int radius = kernel.length / 2;
        int weight = 0;
        for (int value : kernel) {
            weight += value;
        }
        GrayU8 out = new GrayU8(input.width, input.height);
        for (int y = 0; y < input.height; y++) {
            for (int x = 0; x < input.width; x++) {
                int total = 0;
                int used = 0;
                for (int k = 0; k < kernel.length; k++) {
                    int xx = horizontal ? x - radius + k : x;
                    int yy = horizontal ? y : y - radius + k;
                    if (xx < 0 || yy < 0 || xx >= input.width || yy >= input.height) {
                        continue;
                    }
                    total += input.get(xx, yy) * kernel[k];
                    used += kernel[k];
                }
                if (!horizontal && used == weight) {
                    out.set(x, y, (int) ((total + weight / 2) * (1.0 / weight)));
                } else {
                    out.set(x, y, (total + used / 2) / used);
                }
            }
        }
        return out;
    }

    @Test
    void gaussianIsTheKernelAppliedTapByTap() {
        java.util.Random random = new java.util.Random(7);
        for (int[] size : new int[][]{{480, 240}, {97, 31}, {13, 200}, {300, 300}}) {
            GrayU8 gray = new GrayU8(size[0], size[1]);
            for (int y = 0; y < gray.height; y++) {
                for (int x = 0; x < gray.width; x++) {
                    gray.set(x, y, Math.min(255, 40 + 150 * x / gray.width + random.nextInt(60)));
                }
            }
            for (int window : new int[]{9, 20, 41, Math.min(size[0], size[1]) / 12 * 2 + 1}) {
                int[] kernel = Binarize.gaussianKernel(window / 2);
                GrayU8 blur = tapByTap(tapByTap(gray, kernel, true), kernel, false);
                GrayU8 binary = Binarize.localGaussian(gray, window);
                for (int y = 0; y < gray.height; y++) {
                    for (int x = 0; x < gray.width; x++) {
                        int expected = gray.get(x, y) <= blur.get(x, y) * 0.95f ? 1 : 0;
                        assertEquals(expected, binary.get(x, y), size[0] + "x" + size[1] + " window " + window + " at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void gaussianOfALargeFrameTakesTheTimeOfASmallWindow() {
        // The window is a twelfth of the shorter side, 500 pixels here. A kernel that wide cost
        // about 5e10 operations on this frame, close to a minute; the box means take well under
        // a second.
        GrayU8 gray = barsUnderUnevenLight(8000, 6000);
        GrayU8 binary = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> Thresholds.gaussian(gray));
        assertEquals(1, binary.get(4000, 3000));
    }
}
