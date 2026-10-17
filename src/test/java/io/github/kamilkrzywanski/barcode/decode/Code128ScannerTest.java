package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;
import io.github.kamilkrzywanski.barcode.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barcode.image.GrayU8;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class Code128ScannerTest {
    /**
     * A parcel label as BarBeR's ParcelBar photographs it: modules of 1.3 pixels, each pixel the
     * average of what it covers, at eight phases of the bars against the pixels. Whole-pixel edges
     * round the widths to other symbols; edges in quarter pixels read every phase.
     */
    @Test
    void readsModulesUnderTwoPixelsFromTheirEdgesInQuarterPixels() {
        String text = "ED482267729TH";
        for (int eighth = 0; eighth < 8; eighth++) {
            BinaryBitmap image = BinaryBitmap.global(new GrayLuminanceSource(render(text, 1.3, eighth / 8.0)));
            assertNull(Code128Decoder.decodeSpan(image.runs(0, false)), "phase " + eighth);
            Code128Scanner.Found found = Code128Scanner.read(image);
            assertNotNull(found, "phase " + eighth);
            assertEquals(text, found.text());
        }
    }

    private static GrayU8 render(String text, double module, double phase) {
        BitMatrix bars = new Code128Writer().encode(text, BarcodeFormat.CODE_128, 1, 1);
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
