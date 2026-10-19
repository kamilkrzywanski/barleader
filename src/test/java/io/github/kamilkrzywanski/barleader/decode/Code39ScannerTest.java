package io.github.kamilkrzywanski.barleader.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.oned.Code39Writer;
import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;
import io.github.kamilkrzywanski.barleader.image.GrayLuminanceSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class Code39ScannerTest {
    /**
     * A Code 39 with narrow bars of 1.45 pixels and wide bars of twice that, at eight phases of
     * the bars against the pixels. Whole-pixel edges read no phase; edges in quarter pixels read
     * every one.
     */
    @Test
    void readsNarrowBarsUnderOneAndAHalfPixelsFromTheirEdgesInQuarterPixels() {
        String text = "PARCEL-42";
        for (int eighth = 0; eighth < 8; eighth++) {
            BinaryBitmap image = BinaryBitmap.global(new GrayLuminanceSource(BlurredBars.render(
                    new Code39Writer().encode(text, BarcodeFormat.CODE_39, 1, 1), 1.45, eighth / 8.0)));
            assertNull(Code39Decoder.decodeSpan(image.runs(0, false)), "phase " + eighth);
            Code39Scanner.Found found = Code39Scanner.read(image);
            assertNotNull(found, "phase " + eighth);
            assertEquals(text, found.text());
        }
    }
}
