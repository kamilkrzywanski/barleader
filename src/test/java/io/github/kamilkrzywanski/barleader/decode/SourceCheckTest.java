package io.github.kamilkrzywanski.barleader.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.oned.EAN13Writer;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCheckTest {
    private static final String TEXT = "5901234123457";

    /** The symbol at three pixels a module on 80 rows, with 40 pixels of light gray around it. */
    private static GrayU8 symbol() {
        GrayU8 bars = BlurredBars.render(new EAN13Writer().encode(TEXT, BarcodeFormat.EAN_13, 1, 1), 3, 0);
        int margin = 40;
        GrayU8 image = new GrayU8(bars.width + 2 * margin, 80 + 2 * margin);
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                boolean inside = y >= margin && y < margin + 80 && x >= margin && x < margin + bars.width;
                image.data[y * image.stride + x] = (byte) (inside ? bars.data[x - margin] & 255 : 200);
            }
        }
        return image;
    }

    /** The outline of the symbol in {@link #symbol}: its first row from guard to guard, then its last row back. */
    private static double[] outline(GrayU8 image) {
        int bars = new EAN13Writer().encode(TEXT, BarcodeFormat.EAN_13, 1, 1).getWidth();
        // The writer's row holds the quiet zones too: the 95 modules lie in its middle.
        double left = 40 + (bars - 95) / 2.0 * 3;
        return new double[]{left, 45, left + 95 * 3, 45, left + 95 * 3, 115, left, 115};
    }

    @Test
    void linesOfTheFrameThatReadAnotherTextContradictARead() {
        GrayU8 image = symbol();
        double[] outline = outline(image);
        assertFalse(SourceCheck.contradicted(image, outline, TEXT));
        // The frame shows 5901234123457 on every line: a read of another text there is contradicted.
        assertTrue(SourceCheck.contradicted(image, outline, "4006381333931"));
        // Given backwards the outline is the same symbol.
        double[] turned = {outline[4], outline[5], outline[6], outline[7], outline[0], outline[1], outline[2], outline[3]};
        assertFalse(SourceCheck.contradicted(image, turned, TEXT));
        assertTrue(SourceCheck.contradicted(image, turned, "4006381333931"));
    }

    /**
     * The pixels of the frame follow the modules of the text the symbol shows, either way round,
     * and not those of another symbol. The check is coarse: it tells whole patterns apart, not a
     * text from one that differs in a digit or two.
     */
    @Test
    void thePixelsFollowTheModulesOfTheTextTheyShow() {
        GrayU8 image = symbol();
        double[] outline = outline(image);
        assertTrue(SourceCheck.agrees(image, outline, TEXT));
        assertFalse(SourceCheck.agrees(image, outline, "4006381333931"));
        double[] turned = {outline[4], outline[5], outline[6], outline[7], outline[0], outline[1], outline[2], outline[3]};
        assertTrue(SourceCheck.agrees(image, turned, TEXT));
        // A flat frame follows nothing, and an outline one row high has no two lines apart.
        GrayU8 flat = new GrayU8(400, 160);
        java.util.Arrays.fill(flat.data, (byte) 180);
        assertFalse(SourceCheck.agrees(flat, new double[]{60, 40, 340, 40, 340, 120, 60, 120}, TEXT));
        double[] thin = {outline[0], 80, outline[2], 80, outline[2], 81, outline[0], 81};
        assertFalse(SourceCheck.agrees(image, thin, TEXT));
    }

    /** Lines that read nothing say nothing: a flat frame, and an outline that misses the symbol's ends. */
    @Test
    void linesThatReadNothingContradictNothing() {
        GrayU8 flat = new GrayU8(400, 160);
        java.util.Arrays.fill(flat.data, (byte) 180);
        assertFalse(SourceCheck.contradicted(flat, new double[]{60, 40, 340, 40, 340, 120, 60, 120}, TEXT));
        GrayU8 image = symbol();
        double[] outline = outline(image);
        double[] half = {outline[0], outline[1], outline[0] + 150, outline[3], outline[0] + 150, outline[5], outline[6], outline[7]};
        assertFalse(SourceCheck.contradicted(image, half, "4006381333931"));
    }
}
