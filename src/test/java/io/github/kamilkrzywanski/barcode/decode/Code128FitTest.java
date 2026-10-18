package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;
import io.github.kamilkrzywanski.barcode.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barcode.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Code128FitTest {
    /**
     * A parcel code with modules of one pixel at eight phases of the bars against the pixels. The
     * row scan, with its edges in quarter pixels too, reads only some phases; the fit reads every
     * one, the right way round and upside down.
     */
    @Test
    void readsModulesOfOnePixelAtEveryPhase() {
        String text = "ED482267729TH";
        // Twelve modules of quiet zone on each side, as on the parcel labels.
        BitMatrix bars = new Code128Writer().encode(text, BarcodeFormat.CODE_128, 1, 1, Map.of(EncodeHintType.MARGIN, 24));
        int rowScan = 0;
        for (int eighth = 0; eighth < 8; eighth++) {
            GrayU8 strip = BlurredBars.render(bars, 1.0, eighth / 8.0);
            if (Code128Scanner.read(BinaryBitmap.global(new GrayLuminanceSource(strip))) != null) {
                rowScan++;
            }
            Code128Fit.Found found = Code128Fit.read(strip);
            assertNotNull(found, "phase " + eighth);
            assertEquals(text, found.text());
            assertFalse(found.upsideDown());
            assertEquals(12 + eighth / 8.0, found.left(), 0.75);
            Code128Fit.Found turned = Code128Fit.read(mirror(strip));
            assertNotNull(turned, "mirrored phase " + eighth);
            assertEquals(text, turned.text());
            assertTrue(turned.upsideDown());
        }
        assertTrue(rowScan < 8, rowScan + " phases read by the row scan");
    }

    /** Bars of random widths between quiet zones fit no Code 128 well enough to be returned. */
    @Test
    void returnsNothingForBarsThatAreNoSymbol() {
        Random random = new Random(128);
        for (int trial = 0; trial < 20; trial++) {
            BitMatrix bars = new BitMatrix(180, 1);
            int x = 12;
            boolean bar = true;
            while (x < 168) {
                int width = 1 + random.nextInt(4);
                if (bar) {
                    bars.setRegion(x, 0, Math.min(width, 168 - x), 1);
                }
                x += width;
                bar = !bar;
            }
            assertNull(Code128Fit.read(BlurredBars.render(bars, 1.0, 0.3)), "trial " + trial);
        }
    }

    private static GrayU8 mirror(GrayU8 strip) {
        GrayU8 out = new GrayU8(strip.width, strip.height);
        for (int y = 0; y < strip.height; y++) {
            for (int x = 0; x < strip.width; x++) {
                out.set(strip.width - 1 - x, y, strip.get(x, y));
            }
        }
        return out;
    }
}
