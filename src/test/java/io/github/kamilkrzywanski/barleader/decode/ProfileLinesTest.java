package io.github.kamilkrzywanski.barleader.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.oned.EAN13Writer;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileLinesTest {
    private static final String TEXT = "5901234123457";

    /** The symbol on 80 rows with white around it, each pixel scaled by a ramp from {@code dark} at the left to 1 at the right. */
    private static GrayU8 shaded(double module, double dark) {
        GrayU8 bars = BlurredBars.render(new EAN13Writer().encode(TEXT, BarcodeFormat.EAN_13, 1, 1), module, 0);
        int margin = 40;
        GrayU8 image = new GrayU8(bars.width + 2 * margin, 80 + 2 * margin);
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                boolean inside = y >= margin && y < margin + 80 && x >= margin && x < margin + bars.width;
                int value = inside ? bars.data[x - margin] & 255 : 200;
                double ramp = dark + (1 - dark) * x / (image.width - 1);
                image.data[y * image.stride + x] = (byte) Math.round(value * ramp);
            }
        }
        return image;
    }

    private static ProfileLines.Quad box(GrayU8 image) {
        return new ProfileLines.Quad(30, 45, image.width - 30, 45, image.width - 30, image.height - 45, 30, image.height - 45);
    }

    /**
     * A symbol whose left end is a third as bright as its right: one level across a row puts the
     * dark end's spaces with the bars, the cut between each peak and valley does not.
     */
    @Test
    void readsASymbolUnderShadingFromItsPeaksAndValleys() {
        GrayU8 image = shaded(3.0, 0.35);
        List<ProfileLines.Found> found = ProfileLines.read(image, List.of(box(image)), EnumSet.of(Symbology.EAN_13));
        assertEquals(1, found.size());
        assertEquals(TEXT, found.get(0).text());
        assertEquals(Symbology.EAN_13, found.get(0).symbology());
        assertEquals("scan-line", found.get(0).profile());
        assertTrue(found.get(0).support() >= 2, "lines " + found.get(0).support());
        assertTrue(found.get(0).leftX() > 30 && found.get(0).rightX() < image.width - 30);
    }

    /** The same box upside down reads the same text against the line. */
    @Test
    void readsABoxGivenUpsideDown() {
        GrayU8 image = shaded(3.0, 1.0);
        ProfileLines.Quad turned = new ProfileLines.Quad(image.width - 30, image.height - 45, 30, image.height - 45, 30, 45, image.width - 30, 45);
        List<ProfileLines.Found> found = ProfileLines.read(image, List.of(turned), EnumSet.of(Symbology.EAN_13));
        assertEquals(1, found.size());
        assertEquals(TEXT, found.get(0).text());
        assertTrue(found.get(0).upsideDown());
    }

    @Test
    void aFlatBoxAndFormatsItDoesNotReadGiveNothing() {
        GrayU8 flat = new GrayU8(300, 120);
        java.util.Arrays.fill(flat.data, (byte) 180);
        assertTrue(ProfileLines.read(flat, List.of(box(flat)), EnumSet.of(Symbology.EAN_13, Symbology.CODE_128)).isEmpty());
        GrayU8 image = shaded(3.0, 1.0);
        assertTrue(ProfileLines.read(image, List.of(box(image)), EnumSet.of(Symbology.QR_CODE)).isEmpty());
        assertTrue(ProfileLines.read(image, List.of(), EnumSet.of(Symbology.EAN_13)).isEmpty());
    }

    /** The profile of a row of an evenly lit symbol: each of the three cuts finds its bars, and the runs add up to the line. */
    @Test
    void theRunsOfALineCoverItFromEndToEnd() {
        GrayU8 image = shaded(3.0, 1.0);
        ProfileSampler sampler = new ProfileSampler();
        sampler.sample(image, 0, 80, image.width - 1, 80, 1.5, 4096);
        assertTrue(sampler.hasContrast());
        for (int cut = 0; cut < 3; cut++) {
            if (cut == 1) {
                sampler.adaptiveThreshold();
            } else {
                sampler.threshold(cut == 0);
            }
            float sum = 0;
            for (int i = 0; i < sampler.runCount(); i++) {
                assertTrue(sampler.runs()[i] >= 0);
                sum += sampler.runs()[i];
            }
            assertEquals(sampler.samples() - 1, sum, 0.01f, "cut " + cut);
            // 30 bars and 29 spaces of the symbol, and the white before and after it.
            assertEquals(61, sampler.runCount(), "cut " + cut);
            assertTrue(!sampler.firstBlack());
        }
    }
}
