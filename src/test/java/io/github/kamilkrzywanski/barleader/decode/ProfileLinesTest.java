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

    /**
     * The second look at a box: the strongest boxes from a profile sharpened a little, and any of
     * the first eight without their slow shading. Each text comes with the lines that read it.
     */
    @Test
    void theSecondLookReadsTheStrongestBoxesAndThenAnyBox() {
        GrayU8 image = shaded(3.0, 0.6);
        List<ProfileLines.Quad> boxes = List.of(box(image));
        List<ProfileLines.Found> strongest = ProfileLines.recover(image, boxes, List.of(0), List.of(0), EnumSet.of(Symbology.EAN_13));
        assertEquals(TEXT, strongest.get(0).text());
        assertEquals("scan-line-soft", strongest.get(0).profile());
        assertTrue(strongest.get(0).support() >= 3);
        // Without a box that showed guards only the profile without its shading is read.
        List<ProfileLines.Found> any = ProfileLines.recover(image, boxes, List.of(), List.of(), EnumSet.of(Symbology.EAN_13));
        assertEquals(1, any.size());
        assertEquals(TEXT, any.get(0).text());
        assertEquals("scan-line-highpass", any.get(0).profile());
    }

    /**
     * Dark bars on a blue label: the gray of the blue is that of the bars, so the lines of the
     * frame show nothing, and its brightest channel shows the bars dark on light.
     */
    @Test
    void theSecondLookReadsTheBrightestChannelWhereTheGrayShowsNoBars() {
        GrayU8 bars = shaded(3.0, 1.0);
        GrayU8 gray = new GrayU8(bars.width, bars.height);
        java.util.Arrays.fill(gray.data, (byte) 20);
        GrayU8 bright = new GrayU8(bars.width, bars.height);
        for (int i = 0; i < bars.data.length; i++) {
            bright.data[i] = (byte) ((bars.data[i] & 255) < 120 ? 20 : 175);
        }
        List<ProfileLines.Quad> boxes = List.of(box(gray));
        assertTrue(ProfileLines.recover(gray, boxes, List.of(0), List.of(0), EnumSet.of(Symbology.EAN_13)).isEmpty());
        List<ProfileLines.Found> found = ProfileLines.recover(gray, boxes, List.of(0), List.of(0), EnumSet.of(Symbology.EAN_13), () -> bright);
        assertEquals(1, found.size());
        assertEquals(TEXT, found.get(0).text());
        assertEquals("scan-line-colour", found.get(0).profile());
        assertTrue(found.get(0).support() >= 4);
        // The channel is asked for only where a box showed the signs of a symbol.
        assertTrue(ProfileLines.recover(gray, boxes, List.of(), List.of(), EnumSet.of(Symbology.EAN_13), () -> {
            throw new AssertionError("no strongest box");
        }).isEmpty());
    }

    @Test
    void theSecondLookReadsOnlyTheLongRetailCodesAndNothingOnAFlatBox() {
        GrayU8 image = shaded(3.0, 1.0);
        List<ProfileLines.Quad> boxes = List.of(box(image));
        assertTrue(ProfileLines.recover(image, boxes, List.of(0), List.of(0), EnumSet.of(Symbology.CODE_128, Symbology.EAN_8)).isEmpty());
        assertTrue(ProfileLines.recover(image, List.of(), List.of(), List.of(), EnumSet.of(Symbology.EAN_13)).isEmpty());
        GrayU8 flat = new GrayU8(300, 120);
        java.util.Arrays.fill(flat.data, (byte) 180);
        assertTrue(ProfileLines.recover(flat, List.of(box(flat)), List.of(0), List.of(0), EnumSet.of(Symbology.EAN_13)).isEmpty());
    }

    /** A band is the mean of three lines: a row of noise beside the line moves each sample by a third of it. */
    @Test
    void aBandAveragesTheLinesBesideItAndTheWideMaskSharpensAnEdge() {
        GrayU8 image = new GrayU8(60, 9);
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                image.data[y * image.stride + x] = (byte) (y == 3 ? 40 : x < 30 ? 100 : 160);
            }
        }
        ProfileSampler line = new ProfileSampler();
        line.sample(image, 0, 4, 59, 4, 1, 4096);
        ProfileSampler band = new ProfileSampler();
        band.sampleBand(image, 0, 4, 59, 4, 1, 4096, 1);
        // Row 3 is dark, rows 4 and 5 show the step.
        assertEquals(100, line.values()[10], 0.01f);
        assertEquals((100 + 40 + 100) / 3f, band.values()[10], 0.01f);
        assertEquals((160 + 40 + 160) / 3f, band.values()[50], 0.01f);
        line.restoreContrastWide(1.5f);
        assertEquals(100, line.values()[10], 0.01f);
        // Two samples to either side of the step, which lies between samples 29 and 30.
        assertTrue(line.values()[27] < 100 && line.values()[32] > 160, line.values()[27] + " and " + line.values()[32]);
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
