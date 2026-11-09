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
