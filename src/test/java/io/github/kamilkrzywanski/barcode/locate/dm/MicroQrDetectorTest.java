package io.github.kamilkrzywanski.barcode.locate.dm;

import io.github.kamilkrzywanski.barcode.BarcodeHit;
import io.github.kamilkrzywanski.barcode.Symbology;
import io.github.kamilkrzywanski.barcode.decode.BitmapDecoder;
import io.github.kamilkrzywanski.barcode.image.GrayImages;
import io.github.kamilkrzywanski.barcode.image.PixelMap;
import io.github.kamilkrzywanski.barcode.image.Samples;
import io.github.kamilkrzywanski.barcode.image.ZxingLuminance;
import io.github.kamilkrzywanski.barcode.locate.dm.DmGeometry.Bits;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Official bit pictures are the zxing-cpp 3.1.1 {@code MQRDecoderTest} fixtures.
 * The gray images are those pictures scaled with a white quiet zone. They are
 * not part of {@link Samples#all()}.
 */
class MicroQrDetectorTest {
    private static final int QUIET_MODULES = 4;
    private static final int MODULE_PIXELS = 12;

    private static final String[] M1 = {
            "XXXXXXX X X",
            "X     X    ",
            "X XXX X XXX",
            "X XXX X  XX",
            "X XXX X   X",
            "X     X XX ",
            "XXXXXXX X  ",
            "        X  ",
            "XX     X   ",
            " X  XXXXX X",
            "X  XXXXXX X",
    };

    private static final String[] M1_ERROR = {
            "XXXXXXX X X",
            "X     X  XX",
            "X XXX X X  ",
            "X XXX X  XX",
            "X XXX X   X",
            "X     X XX ",
            "XXXXXXX X  ",
            "        X  ",
            "XX     X   ",
            " X  XXXXXX ",
            "X  XXXXXXX ",
    };

    private static final String[] M3L = {
            "XXXXXXX X X X X",
            "X     X    X X ",
            "X XXX X XXXXXXX",
            "X XXX X X X  XX",
            "X XXX X    X XX",
            "X     X X X X X",
            "XXXXXXX  X  XX ",
            "         X X  X",
            "XXXXXX    X X X",
            "   X  XX    XXX",
            "XXX XX XXXX XXX",
            " X    X  XXX X ",
            "X XXXXX XXX X X",
            " X    X  X XXX ",
            "XXX XX X X XXXX",
    };

    private static final String[] M3M = {
            "XXXXXXX X X X X",
            "X     X      XX",
            "X XXX X X XX XX",
            "X XXX X X X    ",
            "X XXX X XX XXXX",
            "X     X XX     ",
            "XXXXXXX  X XXXX",
            "        X  XXX ",
            "X    XX XX X  X",
            "   X X     XX  ",
            "XX  XX  XXXXXXX",
            " X    X       X",
            "XX X X      X  ",
            "   X X    X    ",
            "X X XXXX    XXX",
    };

    private static final String[] M4 = {
            "XXXXXXX X X X X X",
            "X     X XX X   XX",
            "X XXX X  X  X  XX",
            "X XXX X XX  XX XX",
            "X XXX X  X  XXXXX",
            "X     X XX      X",
            "XXXXXXX XX  X  XX",
            "         X  XX XX",
            "X  X XXX    X XXX",
            " XX  X  XX XX  X ",
            "XX  XXXX X XX  XX",
            "    XX XX X XX XX",
            "XXX XXX XXX XX XX",
            "  X X   X   XX  X",
            "X X XX   XXXXX   ",
            "  X X X X   X    ",
            "X   XXXXXXX X X X",
    };

    @Test
    void decodesOfficialM1() {
        assertEquals("123", MicroQrDecoder.decode(grid(M1)));
    }

    @Test
    void decodesOfficialM3Low() {
        assertEquals("Wikipedia", MicroQrDecoder.decode(grid(M3L)));
    }

    @Test
    void decodesOfficialM3Medium() {
        assertEquals("0100019", MicroQrDecoder.decode(grid(M3M)));
    }

    @Test
    void decodesOfficialM4() {
        assertEquals("123456abcdefgh", MicroQrDecoder.decode(grid(M4)));
    }

    @Test
    void rejectsFourBitChecksumError() {
        assertNull(MicroQrDecoder.decode(grid(M1_ERROR)));
    }

    @Test
    void readsFlatMicroQr() {
        assertReads(flat(), "123456abcdefgh");
    }

    /** Hybrid bits of the rendered picture, not the local-average trace. */
    @Test
    void readsARenderedSymbolOnTheScanPath() {
        EnumSet<Symbology> requested = EnumSet.of(Symbology.MICRO_QR);
        List<BarcodeHit> hits = BitmapDecoder.read(
                ZxingLuminance.of(flat()),
                PixelMap.IDENTITY,
                "original-hybrid",
                0,
                0.92,
                requested,
                false,
                false
        );
        assertEquals(1, hits.size(), hits.toString());
        assertEquals("123456abcdefgh", hits.get(0).text());
        assertEquals(Symbology.MICRO_QR, hits.get(0).symbology());
        assertEquals("original-hybrid", hits.get(0).attempt());
        assertEquals(EnumSet.of(Symbology.MICRO_QR), requested);
    }

    @Test
    void readsPerspectiveMicroQr() {
        assertReads(skewed(), "123456abcdefgh");
    }

    @Test
    void leavesStandardQrAlone() {
        BufferedImage qr = null;
        for (Samples.Sample sample : Samples.all()) {
            if ("qr".equals(sample.name())) {
                qr = sample.image();
                break;
            }
        }
        assertTrue(qr != null);
        List<MicroQrDetector.Found> found = MicroQrDetector.read(GrayImages.from(qr));
        assertTrue(found.isEmpty(), found.toString());
    }

    /**
     * Locked to zxing-cpp {@code QRFormatInformationTest::DecodeMicro}.
     * Levels are Low 0, Medium 1, Quality 2. Equality ignores the Hamming distance.
     */
    @Test
    void readsTheOfficialFormatInformation() {
        assertMicro(0x4445, 0, 0);
        assertMicro(0x4172, 1, 0);
        assertMicro(0x5fc0, 2, 0);
        assertMicro(0x5af7, 3, 0);
        assertMicro(0x6793, 0, 1);
        assertMicro(0x62a4, 1, 1);
        assertMicro(0x3e8d, 2, 2);
        assertMicro(0x3BBA, 3, 2);

        MicroQrDecoder.Format expected = MicroQrDecoder.decodeFormat(0x3BBA);
        assertSameMicro(expected, MicroQrDecoder.decodeFormat(0x3BBA ^ 0x01));
        assertSameMicro(expected, MicroQrDecoder.decodeFormat(0x3BBA ^ 0x03));
        assertSameMicro(expected, MicroQrDecoder.decodeFormat(0x3BBA ^ 0x07));
    }

    /**
     * Locked to zxing-cpp {@code QRVersionTest} micro version numbers,
     * {@code GetProvisionalMicroVersionForDimension}, {@code buildFunctionPattern},
     * and {@code QRErrorCorrectionLevelTest::ForMicroBits}.
     * The finder is the 9×9 block. Timing is the top row from column 9 and the left column from row 9.
     */
    @Test
    void matchesOfficialVersionDimensionsAndLevels() {
        assertEquals(0, MicroQrDecoder.versionForDimension(0));
        assertEquals(0, MicroQrDecoder.versionForDimension(10));
        assertEquals(0, MicroQrDecoder.versionForDimension(12));
        assertEquals(0, MicroQrDecoder.versionForDimension(18));
        assertEquals(0, MicroQrDecoder.versionForDimension(21));
        assertEquals(0, MicroQrDecoder.dimensionForVersion(0));
        assertEquals(0, MicroQrDecoder.dimensionForVersion(5));
        for (int i = 1; i <= 4; i++) {
            int dimension = 9 + 2 * i;
            assertEquals(dimension, MicroQrDecoder.dimensionForVersion(i), "dimension " + i);
            assertEquals(i, MicroQrDecoder.versionForDimension(dimension), "version " + i);
            boolean[] pattern = MicroQrDecoder.functionPattern(dimension);
            for (int row = 0; row < 9; row++) {
                for (int col = 0; col < 9; col++) {
                    assertTrue(pattern[row * dimension + col], "finder " + i);
                }
            }
            for (int x = 9; x < dimension; x++) {
                assertTrue(pattern[x], "timing row " + i);
            }
            for (int y = 9; y < dimension; y++) {
                assertTrue(pattern[y * dimension], "timing column " + i);
            }
        }
        int[] levels = {0, 0, 1, 0, 1, 0, 1, 2};
        for (int bits = 0; bits < levels.length; bits++) {
            assertEquals(levels[bits], MicroQrDecoder.ecLevelFromBits(bits), "bits " + bits);
        }
        assertEquals(2, MicroQrDecoder.ecLevelFromBits(-1));
        assertEquals(0, MicroQrDecoder.ecLevelFromBits(8));
    }

    /** Locked to zxing-cpp {@code QRBitMatrixParserTest} M3-L and M3-M codeword spots. */
    @Test
    void readsTheOfficialMicroCodewords() {
        assertMicroCodeword(M3L, 10, 0x00);
        assertMicroCodeword(M3L, 11, 0xD1);
        assertMicroCodeword(M3M, 8, 0x00);
        assertMicroCodeword(M3M, 9, 0x89);
    }

    /**
     * Locked to zxing-cpp {@code QRModeTest::MicroCharacterCount}.
     * Numeric is versions 1–4. Alphanumeric starts at 2, byte and Kanji at 3.
     */
    @Test
    void matchesOfficialCharacterCountBits() {
        assertEquals(3, MicroQrDecoder.countBits(0, 1));
        assertEquals(4, MicroQrDecoder.countBits(0, 2));
        assertEquals(6, MicroQrDecoder.countBits(0, 4));
        assertEquals(3, MicroQrDecoder.countBits(1, 2));
        assertEquals(4, MicroQrDecoder.countBits(2, 3));
        assertEquals(4, MicroQrDecoder.countBits(3, 4));
        int[] numeric = {3, 4, 5, 6};
        int[] alphanumeric = {3, 4, 5};
        int[] bytes = {4, 5};
        int[] kanji = {3, 4};
        for (int version = 1; version <= 4; version++) {
            assertEquals(numeric[version - 1], MicroQrDecoder.countBits(0, version), "numeric " + version);
            if (version >= 2) {
                assertEquals(alphanumeric[version - 2], MicroQrDecoder.countBits(1, version), "alnum " + version);
            }
            if (version >= 3) {
                assertEquals(bytes[version - 3], MicroQrDecoder.countBits(2, version), "byte " + version);
                assertEquals(kanji[version - 3], MicroQrDecoder.countBits(3, version), "kanji " + version);
            }
        }
        assertEquals(0, MicroQrDecoder.countBits(4, 4));
    }

    /** Locked to zxing-cpp {@code QRDataMaskTest} micro masks. */
    @Test
    void matchesTheOfficialDataMasks() {
        for (int mask = 0; mask < 4; mask++) {
            for (int version = 1; version <= 4; version++) {
                int dimension = 9 + 2 * version;
                for (int y = 0; y < dimension; y++) {
                    for (int x = 0; x < dimension; x++) {
                        if (MicroQrDecoder.dataMask(mask, x, y) != officialMicroMask(mask, x, y)) {
                            fail("mask " + mask + " version " + version + " at " + x + "," + y);
                        }
                    }
                }
            }
        }
    }

    private static void assertMicroCodeword(String[] rows, int index, int expected) {
        Bits bits = grid(rows);
        assertEquals(3, MicroQrDecoder.versionForDimension(bits.width));
        MicroQrDecoder.Format format = MicroQrDecoder.readFormat(bits);
        assertTrue(format.valid());
        int[] codewords = MicroQrDecoder.readCodewords(bits, 3, format, 17);
        assertTrue(codewords != null);
        assertEquals(17, codewords.length);
        assertEquals(expected, codewords[index]);
    }

    private static void assertMicro(int bits, int dataMask, int ecLevel) {
        MicroQrDecoder.Format format = MicroQrDecoder.decodeFormat(bits);
        assertTrue(format.hamming <= 3, Integer.toHexString(bits));
        assertEquals(dataMask, format.dataMask, Integer.toHexString(bits));
        assertEquals(ecLevel, format.ecLevel, Integer.toHexString(bits));
    }

    private static void assertSameMicro(MicroQrDecoder.Format expected, MicroQrDecoder.Format actual) {
        assertEquals(expected.dataMask, actual.dataMask);
        assertEquals(expected.ecLevel, actual.ecLevel);
    }

    /** {@code i} is the row and {@code j} is the column. */
    private static boolean officialMicroMask(int mask, int x, int y) {
        int i = y;
        int j = x;
        return switch (mask) {
            case 0 -> i % 2 == 0;
            case 1 -> (i / 2 + j / 3) % 2 == 0;
            case 2 -> ((i * j) % 2 + (i * j) % 3) % 2 == 0;
            case 3 -> ((i + j) % 2 + (i * j) % 3) % 2 == 0;
            default -> false;
        };
    }

    private static BufferedImage flat() {
        return render(M4);
    }

    private static BufferedImage skewed() {
        return Samples.perspective(flat());
    }

    private static void assertReads(BufferedImage image, String text) {
        List<MicroQrDetector.Found> found = MicroQrDetector.read(GrayImages.from(image));
        assertTrue(
                found.stream().anyMatch(hit -> text.equals(hit.text())),
                found.toString()
        );
    }

    private static Bits grid(String[] rows) {
        int height = rows.length;
        int width = rows[0].length();
        Bits bits = new Bits(width, height);
        for (int y = 0; y < height; y++) {
            if (rows[y].length() != width) {
                throw new IllegalArgumentException(rows[y]);
            }
            for (int x = 0; x < width; x++) {
                if (rows[y].charAt(x) == 'X') {
                    bits.set(x, y);
                }
            }
        }
        return bits;
    }

    private static BufferedImage render(String[] rows) {
        int modules = rows.length;
        int span = modules + QUIET_MODULES * 2;
        int size = span * MODULE_PIXELS;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[size * size];
        Arrays.fill(rgb, 0xFFFFFF);
        for (int y = 0; y < modules; y++) {
            if (rows[y].length() != modules) {
                throw new IllegalArgumentException(rows[y]);
            }
            for (int x = 0; x < modules; x++) {
                if (rows[y].charAt(x) != 'X') {
                    continue;
                }
                int originX = (x + QUIET_MODULES) * MODULE_PIXELS;
                int originY = (y + QUIET_MODULES) * MODULE_PIXELS;
                for (int dy = 0; dy < MODULE_PIXELS; dy++) {
                    int row = (originY + dy) * size + originX;
                    for (int dx = 0; dx < MODULE_PIXELS; dx++) {
                        rgb[row + dx] = 0x000000;
                    }
                }
            }
        }
        image.setRGB(0, 0, size, size, rgb, 0, size);
        return image;
    }
}
