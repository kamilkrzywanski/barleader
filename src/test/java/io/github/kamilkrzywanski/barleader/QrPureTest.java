package io.github.kamilkrzywanski.barleader;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QR Codes with damaged finder patterns, as in BoofCV's pathological samples. */
class QrPureTest {
    private static final int MODULE = 8;
    private static final int WHITE = 0xFFFFFF;
    private static final int BLACK = 0x000000;

    private static BufferedImage symbol(String text) {
        Barcode qr = BarcodeWriter.encode(BarcodeType.QR_CODE, text);
        int quiet = 4;
        int size = (qr.width() + 2 * quiet) * MODULE;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int mx = x / MODULE - quiet;
                int my = y / MODULE - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < qr.width() && my < qr.height() && qr.isDark(mx, my);
                image.setRGB(x, y, dark ? BLACK : WHITE);
            }
        }
        return image;
    }

    private static void fill(BufferedImage image, int x0, int y0, int w, int h, int rgb) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                image.setRGB(x, y, rgb);
            }
        }
    }

    /** Finder corners cut off and a dot in each finder's light ring at the height of its centre. */
    private static BufferedImage damaged(String text) {
        BufferedImage image = symbol(text);
        int q = 4 * MODULE;
        int n = (image.getWidth() - 2 * q) / MODULE;
        int[][] finders = {{0, 0}, {n - 7, 0}, {0, n - 7}};
        for (int[] f : finders) {
            int fx = q + f[0] * MODULE;
            int fy = q + f[1] * MODULE;
            for (int[] corner : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) {
                fill(image, fx + corner[0] * MODULE, fy + corner[1] * MODULE, MODULE, MODULE, WHITE);
            }
            fill(image, fx + MODULE + 2, fy + 3 * MODULE + 2, 4, 4, BLACK);
        }
        return image;
    }

    @Test
    void anUprightSymbolWithDamagedFindersReads() {
        String text = "MAILTO:name@myemail.com";
        List<BarcodeHit> hits = new BarcodeReader().read(damaged(text), ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE));
        assertEquals(1, hits.size());
        assertEquals(text, hits.get(0).text());
        assertEquals(Symbology.QR_CODE, hits.get(0).symbology());
        assertEquals("qr-trace", hits.get(0).attempt(), "the finder search does not read it");
    }

    @Test
    void theCornersAreThoseOfTheSymbol() {
        String text = "https://example.com/pure";
        BufferedImage image = damaged(text);
        List<BarcodeHit> hits = new BarcodeReader().read(image, ReadOptions.fast().timeBudgetMillis(Long.MAX_VALUE));
        assertEquals(1, hits.size());
        for (ImagePoint corner : hits.get(0).corners()) {
            assertTrue(corner.x() >= 4 * MODULE - 2 && corner.x() <= image.getWidth() - 4 * MODULE + 2, corner.toString());
            assertTrue(corner.y() >= 4 * MODULE - 2 && corner.y() <= image.getHeight() - 4 * MODULE + 2, corner.toString());
        }
    }
}
