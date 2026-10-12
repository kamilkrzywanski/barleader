package org.krzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.pdf417.PDF417Writer;
import com.google.zxing.pdf417.encoder.Compaction;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pdf417DetectorTest {
    @Test
    void findsTheGuardsOfAnUprightSymbol() throws Exception {
        boolean[][] image = scale(matrix("PDF417"), 3);
        Pdf417Detector.Result result = Pdf417Detector.detect(image, false, true);
        assertNotNull(result);
        assertEquals(0, result.rotation);
        assertTrue(result.bits == image);
        assertEquals(1, result.symbols.size());
        Pdf417Detector.Point[] points = result.symbols.get(0).points;
        for (Pdf417Detector.Point point : points) {
            assertNotNull(point);
            assertTrue(point.x >= 0 && point.x < image[0].length);
            assertTrue(point.y >= 0 && point.y < image.length);
        }
        assertTrue(points[0].x < points[4].x);
        assertTrue(points[4].x < points[6].x);
        assertTrue(points[6].x < points[2].x);
        assertTrue(points[0].y < points[1].y);
        assertTrue(points[1].y - points[0].y >= 10);
    }

    @Test
    void findsASymbolThatWasTurnedUpsideDown() throws Exception {
        boolean[][] image = rotate180(scale(matrix("UPSIDE"), 3));
        Pdf417Detector.Result result = Pdf417Detector.detect(image, false, false);
        assertNotNull(result);
        assertEquals(180, result.rotation);
        assertEquals(1, result.symbols.size());
        Pdf417Detector.Point[] points = result.symbols.get(0).points;
        assertNotNull(points[0]);
        assertNotNull(points[2]);
        assertTrue(points[0].x < points[2].x);
    }

    @Test
    void rejectsABlankAndANull() {
        assertNull(Pdf417Detector.detect(null, false, true));
        assertNull(Pdf417Detector.detect(new boolean[40][40], false, true));
        assertNull(Pdf417Detector.detect(new boolean[0][], false, true));
    }

    private static boolean[][] matrix(String text) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.PDF417_COMPACTION, Compaction.TEXT);
        hints.put(EncodeHintType.MARGIN, 4);
        var modules = new PDF417Writer().encode(text, BarcodeFormat.PDF_417, 1, 1, hints);
        boolean[][] image = new boolean[modules.getHeight()][modules.getWidth()];
        for (int y = 0; y < modules.getHeight(); y++) {
            for (int x = 0; x < modules.getWidth(); x++) {
                image[y][x] = modules.get(x, y);
            }
        }
        return image;
    }

    private static boolean[][] scale(boolean[][] image, int factor) {
        boolean[][] out = new boolean[image.length * factor][image[0].length * factor];
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[0].length; x++) {
                if (!image[y][x]) {
                    continue;
                }
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        out[y * factor + dy][x * factor + dx] = true;
                    }
                }
            }
        }
        return out;
    }

    private static boolean[][] rotate180(boolean[][] image) {
        int height = image.length;
        int width = image[0].length;
        boolean[][] turned = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                turned[height - 1 - y][width - 1 - x] = image[y][x];
            }
        }
        return turned;
    }
}
