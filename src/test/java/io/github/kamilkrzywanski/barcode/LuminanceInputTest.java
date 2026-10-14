package io.github.kamilkrzywanski.barcode;

import io.github.kamilkrzywanski.barcode.image.GrayImages;
import io.github.kamilkrzywanski.barcode.image.GrayU8;
import io.github.kamilkrzywanski.barcode.image.Samples;
import io.github.kamilkrzywanski.barcode.image.Samples.Kind;
import io.github.kamilkrzywanski.barcode.image.Samples.Sample;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A luminance array reads the same symbols as the image it came from, with or without row padding. */
class LuminanceInputTest {
    private final BarcodeReader reader = BarcodeReader.sequential();

    @Test
    void readsEveryCleanSampleFromLuminance() {
        for (Sample sample : Samples.all()) {
            if (sample.kind() != Kind.CLEAN) {
                continue;
            }
            GrayU8 gray = GrayImages.from(sample.image());
            List<BarcodeHit> hits = reader.read(gray.data, gray.width, gray.height, ReadOptions.fast());
            assertEquals(1, hits.size(), sample.name());
            assertEquals(sample.expected().get(0), hits.get(0).text(), sample.name());
        }
    }

    @Test
    void honoursOffsetAndRowStride() {
        Sample sample = Samples.all().get(0);
        GrayU8 gray = GrayImages.from(sample.image());
        int offset = 13;
        int stride = gray.width + 7;
        byte[] padded = new byte[offset + stride * gray.height];
        Arrays.fill(padded, (byte) 0x55);
        for (int y = 0; y < gray.height; y++) {
            System.arraycopy(gray.data, y * gray.width, padded, offset + y * stride, gray.width);
        }
        List<BarcodeHit> plain = reader.read(gray.data, gray.width, gray.height, ReadOptions.fast());
        List<BarcodeHit> strided = reader.read(padded, offset, stride, gray.width, gray.height, ReadOptions.fast());
        assertEquals(plain, strided);
    }

    @Test
    void rejectsAnArrayTooShortForTheImage() {
        byte[] pixels = new byte[99];
        assertThrows(IllegalArgumentException.class, () -> reader.read(pixels, 10, 10, ReadOptions.fast()));
        assertThrows(IllegalArgumentException.class, () -> reader.read(pixels, 0, 5, 10, 9, ReadOptions.fast()));
        assertThrows(IllegalArgumentException.class, () -> reader.read(pixels, 0, 10, ReadOptions.fast()));
        assertEquals(List.of(), reader.read(new byte[1], 1, 1, ReadOptions.fast()));
    }
}
