package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.oned.Code128Writer;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;
import io.github.kamilkrzywanski.barcode.image.GrayLuminanceSource;
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
            BinaryBitmap image = BinaryBitmap.global(new GrayLuminanceSource(BlurredBars.render(
                    new Code128Writer().encode(text, BarcodeFormat.CODE_128, 1, 1), 1.3, eighth / 8.0)));
            assertNull(Code128Decoder.decodeSpan(image.runs(0, false)), "phase " + eighth);
            Code128Scanner.Found found = Code128Scanner.read(image);
            assertNotNull(found, "phase " + eighth);
            assertEquals(text, found.text());
        }
    }
}
