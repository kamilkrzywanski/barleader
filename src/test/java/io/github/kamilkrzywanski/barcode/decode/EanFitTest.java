package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.EAN13Writer;
import io.github.kamilkrzywanski.barcode.EanAddOn;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.BinaryBitmap;
import io.github.kamilkrzywanski.barcode.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barcode.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EanFitTest {
    /**
     * EAN-13 and UPC-A with modules of 0.9 to 2 pixels at eight phases of the bars against the
     * pixels, both ways round. The row scan reads only some of them. The fit reads at least seven
     * phases of each, all eight but where a module of exactly one pixel puts every edge on a pixel
     * centre, and never another text.
     */
    @Test
    void readsModulesOfAboutOnePixelAtEveryPhase() {
        int rowScan = 0;
        int strips = 0;
        for (String text : new String[]{"5901234123457", "4006381333931", "0012345678905"}) {
            // Twelve modules of quiet zone on each side.
            BitMatrix bars = new EAN13Writer().encode(text, BarcodeFormat.EAN_13, 1, 1, Map.of(EncodeHintType.MARGIN, 24));
            for (double module : new double[]{0.9, 1.0, 1.25, 1.5, 2.0}) {
                int read = 0;
                int turnedRead = 0;
                for (int eighth = 0; eighth < 8; eighth++) {
                    GrayU8 strip = BlurredBars.render(bars, module, eighth / 8.0);
                    strips++;
                    if (UpcEanScanner.read(BinaryBitmap.global(new GrayLuminanceSource(strip)), EnumSet.of(Symbology.EAN_13), EanAddOn.IGNORE) != null) {
                        rowScan++;
                    }
                    String at = text + " module " + module + " phase " + eighth;
                    EanFit.Found found = EanFit.read(strip);
                    if (found != null) {
                        assertEquals(text, found.text(), at);
                        assertFalse(found.upsideDown(), at);
                        assertEquals(12 * module + eighth / 8.0, found.left(), 0.75 * module + 0.5, at);
                        read++;
                    }
                    EanFit.Found turned = EanFit.read(mirror(strip));
                    if (turned != null) {
                        assertEquals(text, turned.text(), "mirrored " + at);
                        assertTrue(turned.upsideDown(), "mirrored " + at);
                        turnedRead++;
                    }
                }
                assertTrue(read >= 7 && turnedRead >= 7, text + " module " + module + ": " + read + " and " + turnedRead + " of 8");
            }
        }
        assertTrue(rowScan < strips, rowScan + " of " + strips + " strips read by the row scan");
    }

    /** Bars of random widths across 95 modules between quiet zones fit no EAN-13 well enough to be returned. */
    @Test
    void returnsNothingForBarsThatAreNoSymbol() {
        Random random = new Random(13);
        for (int trial = 0; trial < 100; trial++) {
            BitMatrix bars = new BitMatrix(143, 1);
            int x = 24;
            boolean bar = true;
            while (x < 119) {
                int width = 1 + random.nextInt(4);
                if (bar) {
                    bars.setRegion(x, 0, Math.min(width, 119 - x), 1);
                }
                x += width;
                bar = !bar;
            }
            assertNull(EanFit.read(BlurredBars.render(bars, 1.0, 0.3)), "trial " + trial);
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
