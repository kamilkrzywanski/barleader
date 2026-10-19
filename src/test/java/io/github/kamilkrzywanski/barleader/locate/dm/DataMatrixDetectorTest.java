package io.github.kamilkrzywanski.barleader.locate.dm;

import io.github.kamilkrzywanski.barleader.image.GrayU8;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barleader.image.GrayImages;
import io.github.kamilkrzywanski.barleader.image.Samples;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DataMatrixDetectorTest {
    @Test
    void readsCleanSquareDataMatrix() {
        assertReads("datamatrix", "DM-PRO-123");
    }

    @Test
    void readsPerspectiveDataMatrixFromEitherPolarity() {
        assertReads("perspective-datamatrix", "DM-SKEW");
    }

    @Test
    void readsSmallDataMatrixOnALargeLabel() {
        assertReads("datamatrix-on-label", "DM-TILE");
    }

    private static void assertReads(String name, String text) {
        Samples.Sample sample = Samples.all().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
        GrayU8 gray = GrayImages.from(sample.image());
        List<DataMatrixDetector.Found> found = DataMatrixDetector.read(gray);
        if (found.stream().noneMatch(hit -> text.equals(hit.text()))) {
            found = DataMatrixDetector.read(GrayImages.invert(gray));
        }
        assertTrue(
                found.stream().anyMatch(hit -> text.equals(hit.text())),
                found.toString()
        );
    }
}
