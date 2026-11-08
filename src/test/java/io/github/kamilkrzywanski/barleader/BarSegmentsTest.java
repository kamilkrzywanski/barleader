package io.github.kamilkrzywanski.barleader;

import io.github.kamilkrzywanski.barleader.decode.BitmapDecoder;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarSegmentsTest {
    private static final String[] L = {"0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111",
            "0111011", "0110111", "0001011"};
    private static final String[] PARITY = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL",
            "LGLGLG", "LGLGGL", "LGGLGL"};

    /** The 95 modules of an EAN-13, '1' for a bar. */
    private static String modules(String text) {
        StringBuilder bars = new StringBuilder("101");
        String parity = PARITY[text.charAt(0) - '0'];
        for (int i = 1; i <= 6; i++) {
            String l = L[text.charAt(i) - '0'];
            if (parity.charAt(i - 1) == 'G') {
                StringBuilder g = new StringBuilder();
                for (int k = 6; k >= 0; k--) {
                    g.append(l.charAt(k) == '0' ? '1' : '0');
                }
                l = g.toString();
            }
            bars.append(l);
        }
        bars.append("01010");
        for (int i = 7; i <= 12; i++) {
            for (char c : L[text.charAt(i) - '0'].toCharArray()) {
                bars.append(c == '0' ? '1' : '0');
            }
        }
        return bars.append("101").toString();
    }

    /**
     * A symbol close to the lens: modules of 24 pixels make a box longer than the 2,400 pixels a crop
     * is sampled at, which used to drop the box. It is now sampled more coarsely.
     */
    @Test
    void aSymbolLongerThanACropIsSampledCoarserNotDropped() {
        String text = "4006381333931";
        String bars = modules(text);
        int module = 24;
        int width = 95 * module + 400;
        int height = 900;
        GrayU8 gray = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int m = (x - 200) / module;
                boolean bar = x >= 200 && m < 95 && y >= 150 && y < 750 && bars.charAt(m) == '1';
                gray.data[y * width + x] = (byte) (bar ? 20 : 235);
            }
        }
        List<LinearBand.Crop> crops = BarSegments.find(gray, 8);
        assertFalse(crops.isEmpty());
        boolean read = false;
        for (LinearBand.Crop crop : crops) {
            assertTrue(crop.image().width <= 2400 + 40, crop.image().width + " px");
            List<BarcodeHit> hits = BitmapDecoder.read(crop.image(), crop.toSource(), "linear-band", crop.degrees(), 0.7,
                    EnumSet.of(Symbology.EAN_13), false, false);
            read |= !hits.isEmpty() && hits.get(0).text().equals(text);
        }
        assertTrue(read);
    }

    /**
     * An Arte-Lab photo that upright reads only on the halved frame: turned by 90 degrees its bars
     * lie along the rows, and the halved frame is read once more turned back.
     */
    @Test
    void aVerticalSymbolReadsOnTheTurnedVariants() throws IOException {
        BufferedImage image;
        try (InputStream in = BarSegmentsTest.class.getResourceAsStream("/artelab/Dataset2/Foto(515).jpg")) {
            image = ImageIO.read(in);
        }
        BufferedImage turned = new BufferedImage(image.getHeight(), image.getWidth(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                turned.setRGB(image.getHeight() - 1 - y, x, image.getRGB(x, y));
            }
        }
        List<BarcodeHit> hits = new BarcodeReader().read(turned, ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE));
        assertEquals(1, hits.size());
        assertEquals("5030930017491", hits.get(0).text());
        assertTrue(hits.get(0).attempt().endsWith("-90"), hits.get(0).attempt());
    }
}
