package io.github.kamilkrzywanski.barcode.image;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** {@link GrayImages#median3} against the plain definition: sort the nine clamped neighbours, take the fifth. */
class MedianTest {
    @Test
    void matchesSortingTheNineNeighbours() {
        Random random = new Random(11);
        int[][] sizes = {{1, 1}, {1, 5}, {5, 1}, {2, 2}, {2, 3}, {3, 3}, {7, 4}, {64, 33}, {129, 70}};
        for (int[] size : sizes) {
            for (int round = 0; round < 4; round++) {
                GrayU8 gray = new GrayU8(size[0], size[1]);
                int levels = round == 0 ? 2 : round == 1 ? 4 : 256;
                for (int i = 0; i < gray.data.length; i++) {
                    gray.data[i] = (byte) (random.nextInt(levels) * (255 / Math.max(1, levels - 1)));
                }
                assertArrayEquals(reference(gray), GrayImages.median3(gray).data, size[0] + "x" + size[1]);
            }
        }
    }

    private static byte[] reference(GrayU8 gray) {
        byte[] out = new byte[gray.width * gray.height];
        int[] window = new int[9];
        for (int y = 0; y < gray.height; y++) {
            for (int x = 0; x < gray.width; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = Math.max(0, Math.min(gray.width - 1, x + dx));
                        int sy = Math.max(0, Math.min(gray.height - 1, y + dy));
                        window[n++] = gray.get(sx, sy);
                    }
                }
                Arrays.sort(window);
                out[y * gray.width + x] = (byte) window[4];
            }
        }
        return out;
    }
}
