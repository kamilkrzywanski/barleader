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
    void theSameTextFarApartOrOfAnotherLengthStaysTwoHits() {
        HitCollector hits = new HitCollector();
        assertTrue(hits.add(line("A1", 0, 0, 200, 0)));
        assertTrue(hits.add(line("A1", 0, 150, 200, 150)));
        assertTrue(hits.add(line("A1", 400, 0, 500, 0)));
        assertEquals(3, hits.list().size());
    }
}
