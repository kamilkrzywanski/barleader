package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.BarcodeHit;
import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.Symbology;
import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitCollectorTest {
    private static BarcodeHit line(String text, double x0, double y0, double x1, double y1) {
        return new BarcodeHit(text, Symbology.CODE_128, List.of(new ImagePoint(x0, y0), new ImagePoint(x1, y1)), 0, 0.9, "test");
    }

    @Test
    void twoRowsOfOneTallLinearSymbolAreOneHit() {
        HitCollector hits = new HitCollector();
        assertTrue(hits.add(line("10064908", 18, 103, 378, 103)));
        assertFalse(hits.add(line("10064908", 18, 17, 378, 17)));
        assertEquals(1, hits.list().size());
    }

    @Test
    void aPartialPdf417DetectionOnTheOutlineIsTheSameSymbol() {
        HitCollector hits = new HitCollector();
        assertTrue(hits.add(new BarcodeHit("A larger PDF417", Symbology.PDF_417, List.of(new ImagePoint(157, 169), new ImagePoint(830, 169),
                new ImagePoint(833, 266), new ImagePoint(165, 423)), 0, 0.92, "original-hybrid")));
        assertFalse(hits.add(new BarcodeHit("A larger PDF417", Symbology.PDF_417, List.of(new ImagePoint(155, 169), new ImagePoint(158, 213)),
                0, 0.74, "illumination")));
        assertTrue(hits.add(new BarcodeHit("A larger PDF417", Symbology.PDF_417, List.of(new ImagePoint(1000, 169), new ImagePoint(1600, 169),
                new ImagePoint(1600, 423), new ImagePoint(1000, 423)), 0, 0.92, "original-hybrid")));
        assertEquals(2, hits.list().size());
    }

    @Test
    void aUpcEReadFromTheLeftHalfOfAnEan13IsDropped() {
        HitCollector hits = new HitCollector();
        BarcodeHit ean = new BarcodeHit("4000492512476", Symbology.EAN_13, List.of(new ImagePoint(10, 50), new ImagePoint(300, 50)), -1, 0.92, "test");
        BarcodeHit half = new BarcodeHit("10004924", Symbology.UPC_E, List.of(new ImagePoint(10, 60), new ImagePoint(150, 60)), -1, 0.7, "linear-band");
        assertTrue(hits.add(ean));
        assertFalse(hits.add(half));
        assertEquals(List.of(ean), hits.list());

        HitCollector reversed = new HitCollector();
        assertTrue(reversed.add(half));
        assertTrue(reversed.add(ean));
        assertEquals(List.of(ean), reversed.list());

        HitCollector other = new HitCollector();
        assertTrue(other.add(ean));
        assertTrue(other.add(new BarcodeHit("01234565", Symbology.UPC_E, List.of(new ImagePoint(400, 60), new ImagePoint(500, 60)), -1, 0.7, "test")));
        assertEquals(2, other.list().size());
    }

    /** White, with bars of widths 2 to 5 px from x = 20 to 300 in each band of rows. */
    private static GrayU8 bars(int[]... rows) {
        GrayU8 image = new GrayU8(320, 240);
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                image.set(x, y, 255);
            }
        }
        for (int[] band : rows) {
            for (int y = band[0]; y < band[1]; y++) {
                int x = 20;
                for (int bar = 0; x < 300; bar++) {
                    int width = 2 + (bar * 7) % 4;
                    for (int i = 0; i < width && x + i < 300; i++) {
                        image.set(x + i, y, bar % 2 == 0 ? 0 : 255);
                    }
                    x += width;
                }
            }
        }
        return image;
    }

    @Test
    void scanLinesFarApartOnUnbrokenBarsAreOneSymbol() {
        // The ladder read a Code 93 on its bottom row, the band search on its top, 173 px apart.
        HitCollector hits = new HitCollector(bars(new int[]{10, 220}));
        assertTrue(hits.add(line("CODE93", 20, 200, 300, 200)));
        assertFalse(hits.add(line("CODE93", 20.4, 26.8, 300.6, 29.7)));
        assertEquals(1, hits.list().size());
    }

    @Test
    void aSlantedLineAcrossTheSameBarsIsOneSymbol() {
        // Arte-Lab 05102009146: the ladder reads the row, a band crop a slant through it.
        HitCollector hits = new HitCollector();
        assertTrue(hits.add(line("8004020587048", 325, 564, 1244, 564)));
        assertFalse(hits.add(line("8004020587048", 371, 635, 996, 190)));
        // 05102009081: two slants from the same left end
        assertTrue(hits.add(line("4902030187590", 1590, 472, 2019, 795)));
        assertFalse(hits.add(line("4902030187590", 1595, 465, 2110, 663)));
        assertEquals(2, hits.list().size());
    }

    @Test
    void stackedSymbolsOfTheSameTextStayTwoHits() {
        HitCollector hits = new HitCollector(bars(new int[]{10, 100}, new int[]{130, 220}));
        assertTrue(hits.add(line("A1", 20, 30, 300, 30)));
        assertTrue(hits.add(line("A1", 20, 200, 300, 200)));
        assertEquals(2, hits.list().size());
    }

    @Test
    void theSameTextFarApartOrOfAnotherLengthStaysTwoHits() {
        HitCollector hits = new HitCollector();
        assertTrue(hits.add(line("A1", 0, 0, 200, 0)));
        assertTrue(hits.add(line("A1", 0, 150, 200, 150)));
        assertTrue(hits.add(line("A1", 400, 0, 500, 0)));
        assertEquals(3, hits.list().size());
    }

    /** An EAN-13 of one pixel a module, half a pixel off the grid so that no row reads it, on rows {@code from} to {@code to}. */
    private static void ean13(GrayU8 image, String text, int from, int to) {
        com.google.zxing.common.BitMatrix bars = new com.google.zxing.oned.EAN13Writer().encode(text, com.google.zxing.BarcodeFormat.EAN_13, 1, 1);
        int first = 0;
        while (!bars.get(first, 0)) {
            first++;
        }
        for (int x = 0; x < image.width; x++) {
            double ink = 0;
            for (int m = 0; m < 95; m++) {
                double cover = Math.min(x + 1, 20.5 + m + 1) - Math.max(x, 20.5 + m);
                if (cover > 0 && bars.get(first + m, 0)) {
                    ink += cover;
                }
            }
            for (int y = from; y < to; y++) {
                image.set(x, y, (int) Math.round(230 - 200 * ink));
            }
        }
    }

    private static GrayU8 blank(int width, int height) {
        GrayU8 image = new GrayU8(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.set(x, y, 230);
            }
        }
        return image;
    }

    private static BarcodeHit ean13(String text, double y) {
        return new BarcodeHit(text, Symbology.EAN_13, List.of(new ImagePoint(20.5, y), new ImagePoint(115.5, y)), 0, 0.9, "test");
    }

    /** Two texts read from the bars of one symbol, the right half the same: the one the pixels follow stays, whichever came first. */
    @Test
    void ofTwoTextsOnTheBarsOfOneSymbolTheOneTheFrameShowsStays() {
        String text = "5901234123457";
        String other = "4006381123457";
        GrayU8 image = blank(140, 50);
        ean13(image, text, 5, 45);

        HitCollector wrongFirst = new HitCollector(image);
        assertTrue(wrongFirst.add(ean13(other, 20)));
        assertTrue(wrongFirst.add(ean13(text, 30)));
        assertEquals(1, wrongFirst.list().size());
        assertEquals(text, wrongFirst.list().get(0).text());

        HitCollector rightFirst = new HitCollector(image);
        assertTrue(rightFirst.add(ean13(text, 20)));
        assertFalse(rightFirst.add(ean13(other, 30)));
        assertEquals(1, rightFirst.list().size());
        assertEquals(text, rightFirst.list().get(0).text());

        // A UPC-A and the EAN-13 it is are one text, and stay as they did.
        HitCollector forms = new HitCollector(blank(140, 50));
        assertTrue(forms.add(ean13("0012345678905", 20)));
        assertTrue(forms.add(new BarcodeHit("012345678905", Symbology.UPC_A, List.of(new ImagePoint(20.5, 30), new ImagePoint(115.5, 30)), 0, 0.9, "test")));
        assertEquals(2, forms.list().size());
    }

    /** Where the frame does not say which text its bars are, or the lines are of two symbols, both stay. */
    @Test
    void twoTextsTheFrameDoesNotTellApartBothStay() {
        String text = "5901234123457";
        String other = "4006381123457";
        // Without the frame.
        HitCollector blind = new HitCollector();
        assertTrue(blind.add(ean13(text, 20)));
        assertTrue(blind.add(ean13(other, 30)));
        assertEquals(2, blind.list().size());
        // Two symbols one under the other, as on a Japanese book.
        GrayU8 stacked = blank(140, 100);
        ean13(stacked, text, 5, 40);
        ean13(stacked, other, 60, 95);
        HitCollector two = new HitCollector(stacked);
        assertTrue(two.add(ean13(text, 20)));
        assertTrue(two.add(ean13(other, 80)));
        assertEquals(2, two.list().size());
    }
}
