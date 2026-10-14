package io.github.kamilkrzywanski.barcode.decode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.pdf417.PDF417Writer;
import com.google.zxing.pdf417.encoder.Compaction;
import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.image.GrayImages;
import io.github.kamilkrzywanski.barcode.image.GrayU8;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.image.ZxingLuminance;
import io.github.kamilkrzywanski.barcode.locate.dm.OfficialMatrix;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pdf417ScanningDecoderTest {
    /** The only requested format is PDF417, so ZXing's format set for this call is empty. */
    @Test
    void readsARenderedSymbolOnTheScanPath() throws Exception {
        Map<EncodeHintType, Object> hints = hints();
        hints.put(EncodeHintType.ERROR_CORRECTION, 4);
        BitMatrix modules = new PDF417Writer().encode("PDF417-PRO", BarcodeFormat.PDF_417, 420, 180, hints);
        EnumSet<Symbology> requested = EnumSet.of(Symbology.PDF_417);
        List<BarcodeHit> hits = BitmapDecoder.read(
                ZxingLuminance.of(MatrixToImageWriter.toBufferedImage(modules)),
                PixelMap.IDENTITY,
                "original-hybrid",
                0,
                0.92,
                requested,
                false,
                false
        );
        assertEquals(1, hits.size());
        BarcodeHit hit = hits.get(0);
        assertEquals("PDF417-PRO", hit.text());
        assertEquals(Symbology.PDF_417, hit.symbology());
        assertEquals("original-hybrid", hit.attempt());
        assertEquals(0, hit.orientationDegrees());
        assertFalse(hit.corners().isEmpty());
        assertTrue(requested.contains(Symbology.PDF_417));
    }

    @Test
    void readsAScaledSymbolFromItsGuards() throws Exception {
        assertPhoto("PDF417", null);
        assertPhoto("Hello, PDF417!", null);
        // A run of A is a legal payload. The blurry-photo rejection is about unread codewords,
        // and this symbol is actually printed.
        assertPhoto("A".repeat(52), null);
        Map<EncodeHintType, Object> numeric = hints();
        numeric.put(EncodeHintType.PDF417_COMPACTION, Compaction.NUMERIC);
        assertPhoto("123456789012345678901234567890", numeric);
        Map<EncodeHintType, Object> bytes = hints();
        bytes.put(EncodeHintType.PDF417_COMPACTION, Compaction.BYTE);
        assertPhoto("Byte-Mode-42", bytes);
        Map<EncodeHintType, Object> compact = hints();
        compact.put(EncodeHintType.PDF417_COMPACT, true);
        assertPhoto("Compact-PDF417", compact);
        Map<EncodeHintType, Object> level0 = hints();
        level0.put(EncodeHintType.ERROR_CORRECTION, 0);
        assertPhoto("EC-0", level0);
        Map<EncodeHintType, Object> level5 = hints();
        level5.put(EncodeHintType.ERROR_CORRECTION, 5);
        assertPhoto("EC-5", level5);
        Map<EncodeHintType, Object> utf8 = hints();
        utf8.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        assertPhoto("Zażółć", utf8);
    }

    @Test
    void readsASymbolThatWasTurnedUpsideDown() throws Exception {
        boolean[][] image = rotate180(scale(matrix("UPSIDE", hints()), 3));
        assertEquals("UPSIDE", Pdf417ScanningDecoder.decode(image, false));
    }

    @Test
    void readsASymbolWhoseRowsStepSideways() throws Exception {
        boolean[][] image = shear(scale(matrix("SHEAR", hints()), 3));
        assertEquals("SHEAR", Pdf417ScanningDecoder.decode(image, true));
    }

    @Test
    void correctsOneDamagedCodeword() throws Exception {
        boolean[][] image = scale(matrix("CODEWORD", hints()), 3);
        int cx = image[0].length / 2;
        int cy = image.length / 2;
        for (int y = cy - 2; y <= cy + 2; y++) {
            for (int x = cx - 6; x <= cx + 6; x++) {
                image[y][x] = !image[y][x];
            }
        }
        assertEquals("CODEWORD", Pdf417ScanningDecoder.decode(image, true));
    }

    /**
     * zxing-cpp payloads whose sibling file is {@code .bin}, not text.
     * {@code pdf417-1/09} and {@code pdf417-2/24} are single symbols.
     * {@code pdf417-4} groups 01 and 02 are a structured-append sequence.
     * Group 02 is two pictures. Detection order inside a picture, then file order,
     * is that byte sequence. ImageIO does not read WebP, so those rasters are Pillow PNGs.
     * The sequence index is not part of the result.
     */
    @Test
    void readsTheOfficialStructuredAppendBytes() throws Exception {
        assertBytes(folder("pdf417-1"), "09.bin", "09.png");
        assertBytes(folder("pdf417-2"), "24.bin", "24.png");
        assertBytes(folder("pdf417-4"), "01.bin", "01-01.png");
        assertBytes(folder("pdf417-4"), "02.bin", "02-01.png", "02-02.png");
    }

    @Test
    void rejectsABlankAndANull() {
        assertNull(Pdf417ScanningDecoder.decode(null, true));
        assertNull(Pdf417ScanningDecoder.decode(new boolean[40][40], true));
        assertNull(Pdf417ScanningDecoder.decode(new boolean[0][], true));
        assertEquals(0, Pdf417ScanningDecoder.symbolTexts(null).size());
        assertEquals(0, Pdf417ScanningDecoder.symbolTexts(new boolean[40][40]).size());
        assertEquals(0, Pdf417ScanningDecoder.symbolTexts(new boolean[0][]).size());
    }

    private static void assertBytes(Path root, String bin, String... images) throws Exception {
        StringBuilder text = new StringBuilder();
        for (String name : images) {
            BufferedImage image = ImageIO.read(root.resolve(name).toFile());
            assertNotNull(image, name);
            GrayU8 gray = GrayImages.from(image);
            for (String part : Pdf417ScanningDecoder.symbolTexts(OfficialMatrix.from(gray))) {
                text.append(part);
            }
        }
        byte[] expected = Files.readAllBytes(root.resolve(bin));
        assertArrayEquals(expected, text.toString().getBytes(StandardCharsets.ISO_8859_1), bin);
    }

    private static Path folder(String name) throws URISyntaxException {
        URL marker = Pdf417ScanningDecoderTest.class.getResource("/official-rows/" + name);
        assertNotNull(marker, name);
        return Path.of(marker.toURI());
    }

    private static void assertPhoto(String text, Map<EncodeHintType, ?> hints) throws Exception {
        boolean[][] image = scale(matrix(text, hints), 3);
        assertEquals(text, Pdf417Decoder.decode(image), text);
        assertEquals(text, Pdf417ScanningDecoder.decode(image, true), text);
    }

    private static Map<EncodeHintType, Object> hints() {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        return hints;
    }

    private static boolean[][] matrix(String text, Map<EncodeHintType, ?> hints) throws Exception {
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

    /** One extra pixel of drift every third row. The guard is allowed to wander by five pixels. */
    private static boolean[][] shear(boolean[][] image) {
        int shift = image.length / 3;
        boolean[][] out = new boolean[image.length][image[0].length + shift + 1];
        for (int y = 0; y < image.length; y++) {
            int dx = y / 3;
            for (int x = 0; x < image[0].length; x++) {
                out[y][x + dx] = image[y][x];
            }
        }
        return out;
    }
}
