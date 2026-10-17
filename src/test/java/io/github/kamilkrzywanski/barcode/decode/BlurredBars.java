package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.common.BitMatrix;
import io.github.kamilkrzywanski.barcode.image.GrayU8;

/** Linear symbols drawn as a camera sees small bars: each pixel the average of what it covers. */
final class BlurredBars {
    private BlurredBars() {
    }

    /**
     * Row 0 of {@code bars}, one module per {@code module} pixels and shifted right by {@code phase}
     * pixels, on 12 identical rows.
     */
    static GrayU8 render(BitMatrix bars, double module, double phase) {
        int modules = bars.getWidth();
        int width = (int) Math.ceil(modules * module + 1);
        GrayU8 gray = new GrayU8(width, 12);
        for (int x = 0; x < width; x++) {
            double ink = 0;
            for (int m = 0; m < modules; m++) {
                double cover = Math.min(x + 1, phase + (m + 1) * module) - Math.max(x, phase + m * module);
                if (cover > 0 && bars.get(m, 0)) {
                    ink += cover;
                }
            }
            for (int y = 0; y < gray.height; y++) {
                gray.data[y * gray.stride + x] = (byte) Math.round(200 - 160 * ink);
            }
        }
        return gray;
    }
}
