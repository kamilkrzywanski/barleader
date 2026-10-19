package io.github.kamilkrzywanski.barleader.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.oned.EAN13Writer;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;
import io.github.kamilkrzywanski.barleader.image.GrayLuminanceSource;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpcEanScannerTest {
    /**
     * An EAN-13 with modules of 1.45 pixels, as on many of BarBeR's photos, at eight phases
     * of the bars against the pixels. Whole-pixel edges read only some phases; edges in quarter
     * pixels read every one.
     */
    @Test
    void readsModulesUnderOneAndAHalfPixelsFromTheirEdgesInQuarterPixels() {
        String text = "5901234123457";
        int wholePixels = 0;
        for (int eighth = 0; eighth < 8; eighth++) {
            BinaryBitmap image = BinaryBitmap.global(new GrayLuminanceSource(BlurredBars.render(
                    new EAN13Writer().encode(text, BarcodeFormat.EAN_13, 1, 1), 1.45, eighth / 8.0)));
            if (UpcEanDecoder.decodeSpan(image.runs(0, false), false, false) != null) {
                wholePixels++;
            }
            UpcEanScanner.Found found = UpcEanScanner.read(image, EnumSet.of(Symbology.EAN_13));
            assertNotNull(found, "phase " + eighth);
            assertEquals(text, found.text());
        }
        assertTrue(wholePixels < 4, wholePixels + " phases read from whole pixels");
    }
}
