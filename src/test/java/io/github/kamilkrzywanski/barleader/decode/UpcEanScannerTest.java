package io.github.kamilkrzywanski.barleader.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.oned.EAN13Writer;
import io.github.kamilkrzywanski.barleader.EanAddOn;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.BinaryBitmap;
import io.github.kamilkrzywanski.barleader.image.GrayLuminanceSource;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * The scan starts in the middle of the image. One row of another symbol there, refused, used to
     * end the scan; the rows around it now go on, as zxing-cpp scans until two lines agree.
     */
    @Test
    void aRefusedRowDoesNotEndTheScanOfTheWholePixelRows() {
        String text = "5901234123457";
        String stray = "4006381333931";
        BinaryBitmap image = BinaryBitmap.global(new GrayLuminanceSource(withMiddleRow(text, stray, 3.0, 0)));
        EnumSet<Symbology> ean13 = EnumSet.of(Symbology.EAN_13);

        assertEquals(stray, UpcEanScanner.read(image, ean13).text());
        UpcEanScanner.Found found = UpcEanScanner.read(image, ean13, EanAddOn.IGNORE, read -> !read.text().equals(stray));
        assertNotNull(found);
        assertEquals(text, found.text());
        assertNull(UpcEanScanner.read(image, ean13, EanAddOn.IGNORE, read -> false));
        // Without late reads, as a thresholded image is read, the first row that decodes decides.
        assertNull(UpcEanScanner.read(image, ean13, EanAddOn.IGNORE, read -> !read.text().equals(stray), false));
    }

    /**
     * Rows that decode only with their edges in quarter pixels: there the first row that decodes
     * still decides, as a blurred symbol read backwards repeated one wrong text on several rows.
     */
    @Test
    void aRefusedRowEndsTheScanOfTheQuarterPixelRows() {
        String text = "5901234123457";
        String stray = "4006381333931";
        EnumSet<Symbology> ean13 = EnumSet.of(Symbology.EAN_13);
        int tried = 0;
        for (int eighth = 0; eighth < 8; eighth++) {
            BinaryBitmap image = BinaryBitmap.global(new GrayLuminanceSource(withMiddleRow(text, stray, 1.45, eighth / 8.0)));
            boolean wholePixels = false;
            for (int y = 0; y < image.getHeight(); y++) {
                wholePixels |= UpcEanDecoder.decodeSpan(image.runs(y, false), false, false) != null
                        || UpcEanDecoder.decodeSpan(image.runs(y, true), false, false) != null;
            }
            UpcEanScanner.Found first = UpcEanScanner.read(image, ean13);
            if (wholePixels || first == null || !first.text().equals(stray)) {
                continue;
            }
            tried++;
            assertNull(UpcEanScanner.read(image, ean13, EanAddOn.IGNORE, read -> !read.text().equals(stray)), "phase " + eighth);
        }
        assertTrue(tried > 0, "no phase reads only from quarter pixels");
    }

    /**
     * Whether a read counts depends on its row, so a text refused on two rows is asked about on a
     * third, and not on a fourth.
     */
    @Test
    void aTextRefusedTwiceIsAskedAboutAThirdTime() {
        String text = "5901234123457";
        BinaryBitmap image = BinaryBitmap.global(new GrayLuminanceSource(withMiddleRow(text, text, 3.0, 0)));
        EnumSet<Symbology> ean13 = EnumSet.of(Symbology.EAN_13);
        int[] asked = new int[1];

        UpcEanScanner.Found found = UpcEanScanner.read(image, ean13, EanAddOn.IGNORE, read -> ++asked[0] == 3);
        assertNotNull(found);
        assertEquals(text, found.text());

        asked[0] = 0;
        assertNull(UpcEanScanner.read(image, ean13, EanAddOn.IGNORE, read -> ++asked[0] == 4));
        assertEquals(3, asked[0]);
    }

    /** Twelve rows of {@code text} with the row the scan starts on, the middle one, of {@code middle}. */
    private static GrayU8 withMiddleRow(String text, String middle, double module, double phase) {
        GrayU8 image = BlurredBars.render(new EAN13Writer().encode(text, BarcodeFormat.EAN_13, 1, 1), module, phase);
        GrayU8 other = BlurredBars.render(new EAN13Writer().encode(middle, BarcodeFormat.EAN_13, 1, 1), module, phase);
        int y = image.height / 2;
        System.arraycopy(other.data, y * other.stride, image.data, y * image.stride, image.width);
        return image;
    }
}
