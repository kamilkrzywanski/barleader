package io.github.kamilkrzywanski.barleader.decode;

import io.github.kamilkrzywanski.barleader.BarcodeHit;
import io.github.kamilkrzywanski.barleader.ImagePoint;
import io.github.kamilkrzywanski.barleader.Symbology;
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

    @Test
    void theSameTextFarApartOrOfAnotherLengthStaysTwoHits() {
        HitCollector hits = new HitCollector();
        assertTrue(hits.add(line("A1", 0, 0, 200, 0)));
        assertTrue(hits.add(line("A1", 0, 150, 200, 150)));
        assertTrue(hits.add(line("A1", 400, 0, 500, 0)));
        assertEquals(3, hits.list().size());
    }
}
