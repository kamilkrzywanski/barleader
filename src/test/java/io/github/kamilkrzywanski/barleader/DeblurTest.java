package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.decode.BitmapDecoder;
import io.github.kamilkrzywanski.barleader.image.GrayImages;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import io.github.kamilkrzywanski.barleader.image.PixelMap;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeblurTest {
    /** A BarSegments-like crop: 95 modules of 3 px, a bar every third module, 20 px past each end. */
    private static GrayU8 bars(int height, int smear) {
        int width = 95 * 3 + 40;
        int[] row = new int[width];
        for (int x = 0; x < width; x++) {
            int module = (x - 20) / 3;
            row[x] = x >= 20 && x < width - 20 && module % 3 == 0 ? 30 : 220;
        }
        GrayU8 image = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sum = 0;
                int count = 0;
                for (int k = Math.max(0, x - smear); k <= Math.min(width - 1, x + smear); k++) {
                    sum += row[k];
                    count++;
                }
                image.data[y * width + x] = (byte) (sum / count);
            }
        }
        return image;
    }

    private static int spread(GrayU8 image) {
        int y = image.height / 2;
        int lo = 255;
        int hi = 0;
        for (int x = image.width / 5; x < image.width * 4 / 5; x++) {
            int v = image.data[y * image.width + x] & 255;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return hi - lo;
    }

    @Test
    void sharpCropsAreLeftAlone() {
        assertNull(Deblur.blurred(new LinearBand.Crop(bars(40, 0), PixelMap.IDENTITY, 0)));
    }

    @Test
    void defocusRestoresContrastOfSmearedBars() {
        Deblur deblur = Deblur.blurred(new LinearBand.Crop(bars(40, 3), PixelMap.IDENTITY, 0));
        assertNotNull(deblur);
        GrayU8[] copies = deblur.variants();
        assertTrue(spread(copies[2]) > spread(deblur.original()) + 20,
                spread(copies[2]) + " vs " + spread(deblur.original()));
    }

    /**
     * An Arte-Lab photo taken without autofocus: a crop of {@link BarSegments} as it is reads
     * nothing, its copy with the motion ghost removed reads the label. The reader returns the label
     * too, from whichever step of the ladder gets to it first. Without a time budget, as a cold JVM
     * is slow on the first read.
     */
    @Test
    void blurredArteLabEanReadsOnItsDeghostedCopy() throws IOException {
        BufferedImage image;
        try (InputStream in = DeblurTest.class.getResourceAsStream("/artelab/Dataset2/Foto(483).jpg")) {
            image = ImageIO.read(in);
        }
        Set<Symbology> retail = EnumSet.of(Symbology.EAN_13, Symbology.UPC_A);
        List<String> deghosted = new ArrayList<>();
        for (LinearBand.Crop crop : BarSegments.find(GrayImages.from(image), 8)) {
            Deblur deblur = Deblur.blurred(crop);
            if (deblur == null) {
                continue;
            }
            for (BarcodeHit hit : BitmapDecoder.read(deblur.variants()[0], deblur.toSource(), "linear-band-deghost", deblur.degrees(),
                    0.60, retail, false, false, EanAddOn.IGNORE)) {
                assertTrue(BitmapDecoder.read(crop.image(), crop.toSource(), "linear-band", crop.degrees(), 0.70, retail, false, false,
                        EanAddOn.IGNORE).isEmpty());
                deghosted.add(hit.text());
            }
        }
        assertEquals(List.of("8011642115962"), deghosted);

        List<BarcodeHit> hits = new BarcodeReader().read(image, ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE));
        assertEquals(1, hits.size());
        assertEquals("8011642115962", hits.get(0).text());
    }
}
