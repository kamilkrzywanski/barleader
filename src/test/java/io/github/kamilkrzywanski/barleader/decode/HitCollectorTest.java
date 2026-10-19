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
}
