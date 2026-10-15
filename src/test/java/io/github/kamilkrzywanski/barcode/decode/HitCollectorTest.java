package io.github.kamilkrzywanski.barcode.decode;

import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.Symbology;
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
    void theSameTextFarApartOrOfAnotherLengthStaysTwoHits() {
        HitCollector hits = new HitCollector();
        assertTrue(hits.add(line("A1", 0, 0, 200, 0)));
        assertTrue(hits.add(line("A1", 0, 150, 200, 150)));
        assertTrue(hits.add(line("A1", 400, 0, 500, 0)));
        assertEquals(3, hits.list().size());
    }
}
