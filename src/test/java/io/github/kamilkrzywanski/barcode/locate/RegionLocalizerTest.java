package io.github.kamilkrzywanski.barcode.locate;

import io.github.kamilkrzywanski.barcode.image.GrayU8;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.ImagePoint;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.decode.BitmapDecoder;
import io.github.kamilkrzywanski.barcode.image.GrayImages;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.image.Samples;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionLocalizerTest {
    @Test
    void closedBlobQuadReadsPerspectiveDataMatrix() {
        Samples.Sample sample = Samples.all().stream()
                .filter(item -> item.name().equals("perspective-datamatrix"))
                .findFirst()
                .orElseThrow();
        GrayU8 gray = GrayImages.from(sample.image());
        List<BarcodeHit> hits = new ArrayList<>();
        new RegionLocalizer().localize(gray, PixelMap.IDENTITY, Symbology.defaults(), false, true, new RegionLocalizer.Sink() {
            @Override
            public boolean open() {
                return hits.stream().noneMatch(hit -> "DM-SKEW".equals(hit.text()));
            }

            @Override
            public void decoded(BarcodeHit hit) {
                hits.add(hit);
            }

            @Override
            public void candidate(
                    GrayU8 luma,
                    PixelMap toSource,
                    List<ImagePoint> corners,
                    String attempt,
                    Set<Symbology> formats
            ) {
                hits.addAll(BitmapDecoder.read(luma, toSource, attempt, 0, 0.76, formats, false, false));
            }
        });
        assertTrue(
                hits.stream().anyMatch(hit -> "DM-SKEW".equals(hit.text()) && "blob-quad".equals(hit.attempt())),
                hits.toString()
        );
    }
}
