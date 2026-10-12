package org.krzywanski.barcode.locate.dm;

import org.krzywanski.barcode.image.GrayU8;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import org.krzywanski.barcode.image.GrayImages;
import org.krzywanski.barcode.image.Samples;
import org.krzywanski.barcode.locate.dm.DmGeometry.Bits;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrDetectorTest {
    @Test
    void readsCleanQr() {
        assertReads("qr", "QR-PRO-HELLO");
    }

    /**
     * {@code traceLine} calls {@code stepToEdge} with {@code range == 0}. A zero
     * direction used to stay on one pixel forever. The border is still reached
     * when the step has unit max-norm.
     */
    @Test
    void zeroDirectionStepToEdgeEnds() {
        Bits image = new Bits(30, 10);
        for (int x = 0; x < 8; x++) {
            image.set(x, 5);
        }
        QrCursor.FloatCursor stuck = new QrCursor.FloatCursor(image, 3, 5, 0, 0);
        long start = System.nanoTime();
        assertEquals(0, stuck.stepToEdge(3, 0, true));
        assertTrue((System.nanoTime() - start) / 1_000_000L < 1_000);
        assertEquals(3.0, stuck.x, 0.0);
        assertEquals(5.0, stuck.y, 0.0);

        QrCursor.FloatCursor edge = new QrCursor.FloatCursor(image, 2, 5, 1, 0);
        assertEquals(6, edge.stepToEdge(1, 0, false));
        assertEquals(8.0, edge.x, 0.0);
        assertEquals(5.0, edge.y, 0.0);

        QrCursor.FloatCursor border = new QrCursor.FloatCursor(new Bits(30, 10), 2, 5, 1, 0);
        assertEquals(28, border.stepToEdge(1, 0, false));
        assertEquals(30.0, border.x, 0.0);
    }

    @Test
    void readsPerspectiveQr() {
        assertReads("perspective-qr", "QR-SKEW");
    }

    /**
     * zxing-cpp 3.1.1 also returns no symbol for this gray with the local-average
     * binarizer and tryHarder, including when invert, rotate, and downscale are on.
     * The product scan still reads the sample on {@code qr-trace} after a 3×3 median
     * inside that same attempt. This test locks the raw {@code QrDetector.read} miss.
     */
    @Test
    void leavesNoisyQrToTheSameMissAsZxingCpp() {
        Samples.Sample sample = Samples.all().stream()
                .filter(item -> item.name().equals("qr-noise"))
                .findFirst()
                .orElseThrow();
        List<QrDetector.Found> found = QrDetector.read(GrayImages.from(sample.image()));
        assertTrue(found.isEmpty(), found.toString());
    }

    /**
     * Locked to zxing-cpp {@code QRVersionTest::DecodeVersionInformation} and the
     * 34-word {@code VERSION_DECODE_INFO} table (versions 7 through 40).
     * The one-argument call uses a zero second sample. Up to three flipped bits in the
     * 18-bit field still select that version. Four flipped bits do not.
     */
    @Test
    void readsTheOfficialVersionInformation() {
        assertEquals(7, QrDetector.versionNumber(0x07C94, 0));
        assertEquals(12, QrDetector.versionNumber(0x0C762, 0));
        assertEquals(17, QrDetector.versionNumber(0x1145D, 0));
        assertEquals(22, QrDetector.versionNumber(0x168C9, 0));
        assertEquals(27, QrDetector.versionNumber(0x1B08E, 0));
        assertEquals(32, QrDetector.versionNumber(0x209D5, 0));
        for (int i = 0; i < VERSION_INFO.length; i++) {
            int bits = VERSION_INFO[i];
            int version = i + 7;
            assertEquals(version, QrDetector.versionNumber(bits, 0), "exact " + version);
            assertEquals(version, QrDetector.versionNumber(0, bits), "second " + version);
            for (int b1 = 0; b1 < 18; b1++) {
                int one = bits ^ (1 << b1);
                assertEquals(version, QrDetector.versionNumber(one, 0), "1 bit " + version);
                for (int b2 = b1 + 1; b2 < 18; b2++) {
                    int two = one ^ (1 << b2);
                    assertEquals(version, QrDetector.versionNumber(two, 0), "2 bits " + version);
                    for (int b3 = b2 + 1; b3 < 18; b3++) {
                        int three = two ^ (1 << b3);
                        assertEquals(version, QrDetector.versionNumber(three, 0), "3 bits " + version);
                    }
                }
            }
            assertEquals(0, QrDetector.versionNumber(bits ^ 0x0F, 0), "4 bits " + version);
        }
    }

    /** Raw version bits from zxing-cpp {@code QRVersion.cpp}. Element i is version i + 7. */
    private static final int[] VERSION_INFO = {
            0x07C94, 0x085BC, 0x09A99, 0x0A4D3, 0x0BBF6,
            0x0C762, 0x0D847, 0x0E60D, 0x0F928, 0x10B78,
            0x1145D, 0x12A17, 0x13532, 0x149A6, 0x15683,
            0x168C9, 0x177EC, 0x18EC4, 0x191E1, 0x1AFAB,
            0x1B08E, 0x1CC1A, 0x1D33F, 0x1ED75, 0x1F250,
            0x209D5, 0x216F0, 0x228BA, 0x2379F, 0x24B0B,
            0x2542E, 0x26A64, 0x27541, 0x28C69
    };

    /**
     * Locked to zxing-cpp {@code QRWriterTest} regression picture.
     * Each pair of characters is one module. The picture is 99 by 99.
     */
    @Test
    void readsTheOfficialWriterPicture() {
        List<QrDetector.Found> found = QrDetector.read(spaced(WRITER_GOOGLE));
        assertEquals(1, found.size());
        assertEquals("http://www.google.com/", found.get(0).text());
    }

    /**
     * Locked to zxing-cpp {@code QRWriterTest::OverSize}. The symbol is the ZXing writer.
     * A request of 20 is too small, so both sides come back larger. 256 and 500 by 100
     * are the requested canvas. Each one decodes to the URL. The overflow request is omitted.
     */
    @Test
    void readsTheOfficialWriterSizeRows() throws Exception {
        String url = "http://www.google.com/";
        BitMatrix small = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 20, 20);
        assertTrue(small.getWidth() > 20 && small.getHeight() > 20, small.getWidth() + "x" + small.getHeight());
        assertEquals(url, only(small));

        BitMatrix big = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 256, 256);
        assertEquals(256, big.getWidth());
        assertEquals(256, big.getHeight());
        assertEquals(url, only(big));

        BitMatrix wide = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 500, 100);
        assertEquals(500, wide.getWidth());
        assertEquals(100, wide.getHeight());
        assertEquals(url, only(wide));
    }

    private static String only(BitMatrix matrix) {
        List<QrDetector.Found> found = QrDetector.read(modules(matrix));
        assertEquals(1, found.size());
        return found.get(0).text();
    }

    private static Bits modules(BitMatrix matrix) {
        Bits bits = new Bits(matrix.getWidth(), matrix.getHeight());
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }

    /** Writer art uses a space between modules. Trailing spaces are part of the row. */
    private static Bits spaced(String[] rows) {
        int width = rows[0].length() / 2;
        Bits bits = new Bits(width, rows.length);
        for (int y = 0; y < rows.length; y++) {
            if (rows[y].length() != width * 2) {
                throw new IllegalArgumentException("row " + y);
            }
            for (int x = 0; x < width; x++) {
                if (rows[y].charAt(x * 2) == 'X') {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }

    private static final String[] WRITER_GOOGLE = {
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X                   X X X       X X X X X X             X X X X X X X X X X X X X X X X X X X X X                         ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X                   X X X       X X X X X X             X X X X X X X X X X X X X X X X X X X X X                         ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X                   X X X       X X X X X X             X X X X X X X X X X X X X X X X X X X X X                         ",
            "                        X X X                               X X X             X X X X X X       X X X                   X X X       X X X                               X X X                         ",
            "                        X X X                               X X X             X X X X X X       X X X                   X X X       X X X                               X X X                         ",
            "                        X X X                               X X X             X X X X X X       X X X                   X X X       X X X                               X X X                         ",
            "                        X X X       X X X X X X X X X       X X X                   X X X X X X X X X             X X X             X X X       X X X X X X X X X       X X X                         ",
            "                        X X X       X X X X X X X X X       X X X                   X X X X X X X X X             X X X             X X X       X X X X X X X X X       X X X                         ",
            "                        X X X       X X X X X X X X X       X X X                   X X X X X X X X X             X X X             X X X       X X X X X X X X X       X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X             X X X       X X X X X X X X X X X X       X X X       X X X X X X X X X       X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X             X X X       X X X X X X X X X X X X       X X X       X X X X X X X X X       X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X             X X X       X X X X X X X X X X X X       X X X       X X X X X X X X X       X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X                   X X X X X X X X X X X X X X X       X X X       X X X X X X X X X       X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X                   X X X X X X X X X X X X X X X       X X X       X X X X X X X X X       X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X                   X X X X X X X X X X X X X X X       X X X       X X X X X X X X X       X X X                         ",
            "                        X X X                               X X X       X X X       X X X X X X X X X       X X X                   X X X                               X X X                         ",
            "                        X X X                               X X X       X X X       X X X X X X X X X       X X X                   X X X                               X X X                         ",
            "                        X X X                               X X X       X X X       X X X X X X X X X       X X X                   X X X                               X X X                         ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X       X X X       X X X       X X X       X X X       X X X X X X X X X X X X X X X X X X X X X                         ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X       X X X       X X X       X X X       X X X       X X X X X X X X X X X X X X X X X X X X X                         ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X       X X X       X X X       X X X       X X X       X X X X X X X X X X X X X X X X X X X X X                         ",
            "                                                                        X X X       X X X                   X X X X X X                                                                               ",
            "                                                                        X X X       X X X                   X X X X X X                                                                               ",
            "                                                                        X X X       X X X                   X X X X X X                                                                               ",
            "                        X X X                   X X X       X X X X X X X X X X X X       X X X                   X X X       X X X X X X X X X X X X X X X             X X X                         ",
            "                        X X X                   X X X       X X X X X X X X X X X X       X X X                   X X X       X X X X X X X X X X X X X X X             X X X                         ",
            "                        X X X                   X X X       X X X X X X X X X X X X       X X X                   X X X       X X X X X X X X X X X X X X X             X X X                         ",
            "                              X X X X X X X X X       X X X             X X X             X X X X X X       X X X X X X X X X X X X             X X X X X X       X X X                               ",
            "                              X X X X X X X X X       X X X             X X X             X X X X X X       X X X X X X X X X X X X             X X X X X X       X X X                               ",
            "                              X X X X X X X X X       X X X             X X X             X X X X X X       X X X X X X X X X X X X             X X X X X X       X X X                               ",
            "                                    X X X                   X X X       X X X       X X X       X X X X X X X X X X X X                   X X X X X X X X X X X X                                     ",
            "                                    X X X                   X X X       X X X       X X X       X X X X X X X X X X X X                   X X X X X X X X X X X X                                     ",
            "                                    X X X                   X X X       X X X       X X X       X X X X X X X X X X X X                   X X X X X X X X X X X X                                     ",
            "                              X X X X X X       X X X X X X                         X X X       X X X       X X X                   X X X X X X             X X X X X X                               ",
            "                              X X X X X X       X X X X X X                         X X X       X X X       X X X                   X X X X X X             X X X X X X                               ",
            "                              X X X X X X       X X X X X X                         X X X       X X X       X X X                   X X X X X X             X X X X X X                               ",
            "                        X X X       X X X       X X X X X X X X X                   X X X                                           X X X             X X X X X X X X X X X X                         ",
            "                        X X X       X X X       X X X X X X X X X                   X X X                                           X X X             X X X X X X X X X X X X                         ",
            "                        X X X       X X X       X X X X X X X X X                   X X X                                           X X X             X X X X X X X X X X X X                         ",
            "                        X X X       X X X                         X X X X X X X X X X X X       X X X       X X X X X X X X X                   X X X             X X X                               ",
            "                        X X X       X X X                         X X X X X X X X X X X X       X X X       X X X X X X X X X                   X X X             X X X                               ",
            "                        X X X       X X X                         X X X X X X X X X X X X       X X X       X X X X X X X X X                   X X X             X X X                               ",
            "                                    X X X                   X X X X X X X X X       X X X X X X X X X       X X X X X X             X X X X X X X X X X X X X X X                                     ",
            "                                    X X X                   X X X X X X X X X       X X X X X X X X X       X X X X X X             X X X X X X X X X X X X X X X                                     ",
            "                                    X X X                   X X X X X X X X X       X X X X X X X X X       X X X X X X             X X X X X X X X X X X X X X X                                     ",
            "                                                X X X             X X X X X X             X X X             X X X             X X X X X X X X X       X X X X X X X X X                               ",
            "                                                X X X             X X X X X X             X X X             X X X             X X X X X X X X X       X X X X X X X X X                               ",
            "                                                X X X             X X X X X X             X X X             X X X             X X X X X X X X X       X X X X X X X X X                               ",
            "                        X X X X X X       X X X             X X X                               X X X       X X X X X X X X X X X X X X X X X X X X X X X X X X X                                     ",
            "                        X X X X X X       X X X             X X X                               X X X       X X X X X X X X X X X X X X X X X X X X X X X X X X X                                     ",
            "                        X X X X X X       X X X             X X X                               X X X       X X X X X X X X X X X X X X X X X X X X X X X X X X X                                     ",
            "                                                                        X X X X X X X X X                   X X X       X X X                   X X X       X X X                                     ",
            "                                                                        X X X X X X X X X                   X X X       X X X                   X X X       X X X                                     ",
            "                                                                        X X X X X X X X X                   X X X       X X X                   X X X       X X X                                     ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X       X X X             X X X X X X       X X X       X X X       X X X X X X                                           ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X       X X X             X X X X X X       X X X       X X X       X X X X X X                                           ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X       X X X             X X X X X X       X X X       X X X       X X X X X X                                           ",
            "                        X X X                               X X X             X X X X X X       X X X       X X X       X X X                   X X X X X X X X X X X X X X X                         ",
            "                        X X X                               X X X             X X X X X X       X X X       X X X       X X X                   X X X X X X X X X X X X X X X                         ",
            "                        X X X                               X X X             X X X X X X       X X X       X X X       X X X                   X X X X X X X X X X X X X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X             X X X       X X X X X X       X X X X X X X X X X X X X X X       X X X X X X X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X             X X X       X X X X X X       X X X X X X X X X X X X X X X       X X X X X X X X X                         ",
            "                        X X X       X X X X X X X X X       X X X       X X X             X X X       X X X X X X       X X X X X X X X X X X X X X X       X X X X X X X X X                         ",
            "                        X X X       X X X X X X X X X       X X X             X X X             X X X       X X X X X X X X X X X X X X X             X X X X X X X X X X X X                         ",
            "                        X X X       X X X X X X X X X       X X X             X X X             X X X       X X X X X X X X X X X X X X X             X X X X X X X X X X X X                         ",
            "                        X X X       X X X X X X X X X       X X X             X X X             X X X       X X X X X X X X X X X X X X X             X X X X X X X X X X X X                         ",
            "                        X X X       X X X X X X X X X       X X X             X X X       X X X X X X X X X X X X             X X X X X X X X X                   X X X                               ",
            "                        X X X       X X X X X X X X X       X X X             X X X       X X X X X X X X X X X X             X X X X X X X X X                   X X X                               ",
            "                        X X X       X X X X X X X X X       X X X             X X X       X X X X X X X X X X X X             X X X X X X X X X                   X X X                               ",
            "                        X X X                               X X X                   X X X X X X       X X X       X X X                   X X X X X X X X X X X X X X X                               ",
            "                        X X X                               X X X                   X X X X X X       X X X       X X X                   X X X X X X X X X X X X X X X                               ",
            "                        X X X                               X X X                   X X X X X X       X X X       X X X                   X X X X X X X X X X X X X X X                               ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X X X X X X X       X X X X X X       X X X       X X X                         X X X X X X X X X                         ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X X X X X X X       X X X X X X       X X X       X X X                         X X X X X X X X X                         ",
            "                        X X X X X X X X X X X X X X X X X X X X X       X X X X X X X X X       X X X X X X       X X X       X X X                         X X X X X X X X X                         ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
            "                                                                                                                                                                                                      ",
    };

    private static void assertReads(String name, String text) {
        Samples.Sample sample = Samples.all().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
        GrayU8 gray = GrayImages.from(sample.image());
        List<QrDetector.Found> found = QrDetector.read(gray);
        assertTrue(
                found.stream().anyMatch(hit -> text.equals(hit.text())),
                found.toString()
        );
    }
}
